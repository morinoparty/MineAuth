package party.morino.mineauth.core.plugin

import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.api.AccessInfo
import party.morino.mineauth.api.EndpointRegistrationException
import party.morino.mineauth.api.HttpMethod
import party.morino.mineauth.api.MineAuthApi
import party.morino.mineauth.api.MineAuthRegistration
import party.morino.mineauth.api.RegisteredEndpoint
import party.morino.mineauth.api.RegistrationError
import party.morino.mineauth.core.openapi.registry.EndpointMetadataRegistry
import party.morino.mineauth.core.plugin.annotation.AnnotationProcessor
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.dispatch.NamespaceTable
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * MineAuthApiインターフェースの実装
 * 外部プラグインがエンドポイントを登録するためのエントリーポイント
 *
 * 登録はall-or-nothing: 全ハンドラーの検証エラーを累積し、
 * 1つでもエラーがあれば何もマウントせずEndpointRegistrationExceptionをスローする
 */
class MineAuthApiImpl : MineAuthApi, KoinComponent {

    private val annotationProcessor: AnnotationProcessor by inject()
    private val dispatcher: PluginEndpointDispatcher by inject()
    private val metadataRegistry: EndpointMetadataRegistry by inject()

    // プラグイン名 -> そのプラグインが所有するRegistrationのリスト（自動解除用）
    private val registrations = ConcurrentHashMap<String, CopyOnWriteArrayList<MineAuthRegistrationImpl>>()

    // 名前空間 -> 現在有効なRegistration
    // 古いRegistrationハンドルのunregister()が、再登録された新しいテーブルを消さないようにする
    private val activeByNamespace = ConcurrentHashMap<String, MineAuthRegistrationImpl>()

    companion object {
        // 名前空間の形式: 小文字英数字とハイフン、2〜32文字
        private val NAMESPACE_PATTERN = Regex("[a-z0-9-]{2,32}")
    }

    override fun register(plugin: JavaPlugin, vararg handlers: Any): MineAuthRegistration =
        registerInternal(plugin, plugin.name.lowercase(Locale.ROOT), handlers)

    override fun register(plugin: JavaPlugin, namespace: String, vararg handlers: Any): MineAuthRegistration =
        registerInternal(plugin, namespace, handlers)

    /**
     * 登録処理の本体
     * 名前空間検証 → 全ハンドラーのアノテーション解析 → 重複ルート検出 → アトミックなインストール
     *
     * @param plugin 登録元プラグイン
     * @param namespace URL名前空間
     * @param handlers ハンドラーインスタンスの配列
     * @return 登録結果
     * @throws EndpointRegistrationException 検証エラーがある場合
     */
    @Synchronized
    private fun registerInternal(
        plugin: JavaPlugin,
        namespace: String,
        handlers: Array<out Any>
    ): MineAuthRegistration {
        val errors = mutableListOf<RegistrationError>()

        // 名前空間の形式チェック
        if (!NAMESPACE_PATTERN.matches(namespace)) {
            errors += RegistrationError.InvalidNamespace(
                namespace,
                "must match [a-z0-9-]{2,32} -> pass an explicit namespace via register(plugin, namespace, ...)"
            )
        }

        // 名前空間の所有権チェック
        dispatcher.ownerOf(namespace)?.let { owner ->
            errors += if (owner == plugin.name) {
                RegistrationError.InvalidNamespace(
                    namespace,
                    "already registered by this plugin -> call unregister() first, or register all handlers in one call"
                )
            } else {
                RegistrationError.InvalidNamespace(namespace, "already claimed by plugin '$owner'")
            }
        }

        // ハンドラーが1つも渡されていない場合もエラー
        if (handlers.isEmpty()) {
            errors += RegistrationError.NoEndpoints("(no handlers passed to register)")
        }

        // 全ハンドラーを解析（エラーを累積する）
        val endpoints = mutableListOf<EndpointMetadata>()
        for (handler in handlers) {
            annotationProcessor.process(handler).fold(
                { errors.addAll(it) },
                { endpoints.addAll(it) }
            )
        }

        // 名前空間内の重複ルートを検出（ハンドラー間の重複も含む）
        // パラメータ名を消した構造で比較する: /users/{uuid} と /users/{name} は
        // 同一リクエストにマッチしてしまうため、名前が違っても重複として扱う
        endpoints.groupBy { endpoint ->
            endpoint.httpMethod to endpoint.pathSegments.map { segment ->
                when (segment) {
                    is PathSegment.Literal -> segment.value
                    is PathSegment.Param -> "{}"
                }
            }
        }
            .filterValues { it.size > 1 }
            .forEach { (key, duplicates) ->
                duplicates.drop(1).forEach { endpoint ->
                    errors += RegistrationError.DuplicateRoute(
                        handlerClass = endpoint.handlerInstance::class.qualifiedName ?: "unknown",
                        function = endpoint.method.name,
                        httpMethod = key.first.toApi(),
                        path = endpoint.path
                    )
                }
            }

        // all-or-nothing: 1つでもエラーがあれば何もマウントしない
        if (errors.isNotEmpty()) {
            throw EndpointRegistrationException(errors)
        }

        // ディスパッチャとOpenAPIレジストリへアトミックにインストール
        val basePath = "/api/v1/plugins/$namespace"
        dispatcher.install(namespace, NamespaceTable(plugin.name, basePath, endpoints))
        metadataRegistry.register(namespace, basePath, endpoints)

        // 公開用のエンドポイント情報を構築
        val registeredEndpoints = endpoints.map { endpoint ->
            RegisteredEndpoint(
                httpMethod = endpoint.httpMethod.toApi(),
                fullPath = basePath + endpoint.path,
                handlerClass = endpoint.handlerInstance::class.qualifiedName ?: "unknown",
                functionName = endpoint.method.name,
                access = endpoint.access.toApi()
            )
        }

        val registration = MineAuthRegistrationImpl(
            plugin = plugin,
            namespace = namespace,
            basePath = basePath,
            endpoints = registeredEndpoints,
            onUnregister = ::handleUnregister
        )
        registrations.computeIfAbsent(plugin.name) { CopyOnWriteArrayList() }.add(registration)
        activeByNamespace[namespace] = registration

        plugin.logger.info("MineAuth: mounted ${registeredEndpoints.size} endpoint(s) under $basePath")
        return registration
    }

    /**
     * Registrationの登録解除処理
     * ディスパッチャとOpenAPIレジストリから削除する
     *
     * registerInternalと同一モニターで同期し、check-then-actの競合を防ぐ。
     * 古いハンドルからの呼び出し（unregister後に同じ名前空間が再登録されたケース）では
     * 現在有効なテーブルには触れない。
     */
    @Synchronized
    private fun handleUnregister(registration: MineAuthRegistrationImpl) {
        if (activeByNamespace[registration.namespace] === registration) {
            dispatcher.uninstall(registration.namespace)
            metadataRegistry.unregister(registration.namespace)
            activeByNamespace.remove(registration.namespace)
        }
        registrations[registration.plugin.name]?.remove(registration)
    }

    /**
     * プラグインが所有する全Registrationを解除する
     * PluginDisableEventから呼び出される（クラスローダーリーク防止）
     *
     * ハンドル経由で解除することで冪等性フラグを立て、
     * 保持されたままの古いハンドルが後から二重解除するのを防ぐ
     *
     * @param pluginName 無効化されたプラグイン名
     */
    fun unregisterAll(pluginName: String) {
        registrations.remove(pluginName)?.forEach { it.unregister() }
    }

    /** 内部のHTTPメソッド型を公開APIの型に変換する */
    private fun HttpMethodType.toApi(): HttpMethod = HttpMethod.valueOf(name)

    /** 内部のアクセス制御型を公開APIの型に変換する */
    private fun EndpointAccess.toApi(): AccessInfo = when (this) {
        is EndpointAccess.Public -> AccessInfo.Public
        is EndpointAccess.Authenticated -> AccessInfo.Authenticated(permission, callers)
    }
}
