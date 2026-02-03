package party.morino.mineauth.core.plugin

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.api.RegisterHandler
import party.morino.mineauth.core.openapi.registry.EndpointMetadataRegistry
import party.morino.mineauth.core.plugin.annotation.AnnotationProcessor
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.route.RouteBuilder

/**
 * RegisterHandlerインターフェースの実装
 * 外部プラグインがエンドポイントを登録するためのエントリーポイント
 *
 * @property context プラグインのコンテキスト情報
 */
class RegisterHandlerImpl(
    private val context: PluginContext
) : RegisterHandler, KoinComponent {

    private val annotationProcessor: AnnotationProcessor by inject()
    private val routeBuilder: RouteBuilder by inject()
    private val routeRegistry: PluginRouteRegistry by inject()
    private val metadataRegistry: EndpointMetadataRegistry by inject()

    // 登録済みエンドポイントメタデータ
    private val registeredEndpoints = mutableListOf<EndpointMetadata>()

    /**
     * ハンドラーインスタンスを登録する
     * アノテーションを解析してルートを生成し、レジストリに登録する
     *
     * @param endpoints 登録するハンドラーインスタンス（可変長引数）
     * @return このRegisterHandlerインスタンス（メソッドチェーン用）
     */
    override fun register(vararg handlers: Any): RegisterHandler {
        for (handler in handlers) {
            // アノテーションを処理
            annotationProcessor.process(handler).fold(
                { error ->
                    // エラーをログに記録
                    context.plugin.logger.warning(
                        "Failed to process handler ${handler::class.simpleName}: $error"
                    )
                },
                { metadata ->
                    this.registeredEndpoints.addAll(metadata)
                }
            )
        }

        // ルートを再構築してレジストリに登録
        rebuildRoutes()

        return this
    }

    /**
     * 登録されたエンドポイントからルート設定を再構築する
     */
    private fun rebuildRoutes() {
        val currentEndpoints = this.registeredEndpoints.toList()
        val routeConfig: io.ktor.server.routing.Route.() -> Unit = {
            routeBuilder.buildRoutes(this, context, currentEndpoints)
        }
        // ルートを登録
        routeRegistry.register(context.plugin.name, routeConfig)

        // OpenAPI生成用にメタデータも登録
        metadataRegistry.register(context.plugin.name, context.basePath, currentEndpoints)
    }
}
