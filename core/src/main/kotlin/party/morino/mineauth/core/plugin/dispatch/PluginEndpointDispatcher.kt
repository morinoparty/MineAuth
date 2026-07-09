package party.morino.mineauth.core.plugin.dispatch

import arrow.core.Either
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ErrorResponse
import party.morino.mineauth.core.plugin.route.RequestContext
import party.morino.mineauth.core.plugin.route.RouteExecutor
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import java.util.concurrent.ConcurrentHashMap

/**
 * プラグインエンドポイントのライブディスパッチャ
 *
 * Ktorのルーティングツリーを再構築する代わりに、
 * `/api/v1/plugins/{namespace}/{path...}` への全リクエストを受けて
 * ConcurrentHashMap上のテーブルに対して動的にマッチングする。
 *
 * これにより:
 * - Webサーバー起動後のエンドポイント登録が即座に反映される
 * - unregister()が構造的に可能になる（テーブルからの削除のみ）
 * - リクエストディスパッチとOpenAPI生成が同一のデータソースを参照できる
 */
class PluginEndpointDispatcher(
    private val executor: RouteExecutor,
    private val authHandler: AuthenticationHandler
) : KoinComponent {

    // 名前空間 -> エンドポイントテーブルのマップ（アトミックな差し替えで更新）
    private val tables = ConcurrentHashMap<String, NamespaceTable>()

    /**
     * 名前空間にエンドポイントテーブルをインストールする
     *
     * @param namespace URL名前空間
     * @param table エンドポイントテーブル
     */
    fun install(namespace: String, table: NamespaceTable) {
        tables[namespace] = table
    }

    /**
     * 名前空間のエンドポイントテーブルを削除する（冪等）
     *
     * @param namespace URL名前空間
     */
    fun uninstall(namespace: String) {
        tables.remove(namespace)
    }

    /**
     * 名前空間を所有するプラグイン名を取得する
     *
     * @param namespace URL名前空間
     * @return 所有プラグイン名、未登録の場合null
     */
    fun ownerOf(namespace: String): String? = tables[namespace]?.pluginName

    /**
     * エンドポイントを登録している全プラグイン名を取得する
     *
     * @return 登録済みプラグイン名のリスト（重複なし）
     */
    fun registeredPlugins(): List<String> = tables.values.map { it.pluginName }.distinct()

    /**
     * リクエストをディスパッチする
     * パスマッチング → 405判定 → 認証 → ハンドラー実行の順で処理する
     *
     * @param call ApplicationCall（/api/v1/plugins/{namespace}/{path...}にマッチしたもの）
     */
    suspend fun dispatch(call: ApplicationCall) {
        // 名前空間のテーブルを取得（未登録の名前空間は404）
        val namespace = call.parameters["namespace"]
        if (namespace == null) {
            respondNotFound(call)
            return
        }
        val table = tables[namespace]
        if (table == null) {
            respondNotFound(call)
            return
        }

        // tailcardのセグメントリストを取得（ルート直下アクセス時はnullになる）
        val segments = call.parameters.getAll("path") ?: emptyList()

        // パスがマッチするエンドポイントを全メソッドから収集（404/405の判別のため）
        val pathMatches = table.endpoints.mapNotNull { endpoint ->
            matchPath(endpoint.pathSegments, segments)?.let { endpoint to it }
        }
        if (pathMatches.isEmpty()) {
            respondNotFound(call)
            return
        }

        val allowed = pathMatches.map { it.first.httpMethod.name }.distinct().sorted()
        val rawMethod = call.request.local.method.value

        // OPTIONSはAllowヘッダー付きの204で応答する（RFC 9110）
        if (rawMethod == "OPTIONS") {
            call.response.headers.append(HttpHeaders.Allow, allowed.joinToString(", "))
            call.respond(HttpStatusCode.NoContent)
            return
        }

        // リクエストメソッドで絞り込み（パスは合っているがメソッド違いは405 + Allowヘッダー）
        // HEADはGETエンドポイントで処理する（レスポンスボディはエンジン側で破棄される）
        val requestMethod = HttpMethodType.entries.find { it.name == rawMethod }
            ?: if (rawMethod == "HEAD") HttpMethodType.GET else null
        val methodMatches = pathMatches.filter { it.first.httpMethod == requestMethod }
        if (requestMethod == null || methodMatches.isEmpty()) {
            call.response.headers.append(HttpHeaders.Allow, allowed.joinToString(", "))
            call.respond(
                HttpStatusCode.MethodNotAllowed,
                ErrorResponse("Method not allowed", code = "method_not_allowed")
            )
            return
        }

        // 複数マッチ時はリテラルセグメントが多い（より具体的な）ルートを優先する
        // 例: /shops/mine と /shops/{id} が両方マッチしたら /shops/mine を選ぶ
        val (endpoint, pathParams) = methodMatches.maxByOrNull { specificity(it.first.pathSegments) }!!

        // OpenTelemetryのhttp.route（サーバースパン名）を実エンドポイントのテンプレートに補正する。
        // Ktorのルートは単一のキャッチオール（/api/v1/plugins/{namespace}/{path...}）のため、
        // ディスパッチャがマッチさせた実際のテンプレート（例: /api/v1/plugins/vault/shops/{id}）に
        // 上書きすることで、エンドポイント単位で識別・集計できるようにする。
        // NESTED_CONTROLLER(order=4)はサニタイザのCONTROLLER(order=3)より優先度が高いため、
        // ルート文字列の長短に関わらず必ずこの値が採用される。
        val routeTemplate = table.basePath + endpoint.path
        HttpServerRoute.update(
            Context.current(),
            HttpServerRouteSource.NESTED_CONTROLLER,
            routeTemplate
        )
        // サーバースパンにプラグイン・ルートの識別属性を付与する（トップレベルHTTPスパンを検索可能にする）
        // トレーシング無効時はSpan.current()がNoOpとなり、setAttributeは安全に無視される
        Span.current().apply {
            setAttribute(TelemetryAttributes.PLUGIN_NAMESPACE, namespace)
            setAttribute(TelemetryAttributes.PLUGIN_OWNER, table.pluginName)
            setAttribute(TelemetryAttributes.ROUTE_TEMPLATE, routeTemplate)
            setAttribute(TelemetryAttributes.ENDPOINT_ACCESS, accessLabel(endpoint.access))
        }

        // 認証・認可（失敗時はレスポンス済みで終了）
        val principal = when (val access = endpoint.access) {
            is EndpointAccess.Public -> when (val result = authHandler.optionalPrincipal(call)) {
                is Either.Left -> {
                    executor.respondAuthError(call, result.value)
                    return
                }
                is Either.Right -> result.value
            }

            is EndpointAccess.Authenticated -> when (val result = authHandler.requirePrincipal(call, access)) {
                is Either.Left -> {
                    executor.respondAuthError(call, result.value)
                    return
                }
                is Either.Right -> result.value
            }
        }

        // 認証結果が確定したので、呼び出し元の種別をサーバースパンに記録する
        Span.current().setAttribute(TelemetryAttributes.CALLER_TYPE, callerTypeLabel(principal))

        // ハンドラーを実行
        executor.execute(RequestContext(call, principal, pathParams), endpoint)
    }

    /**
     * アクセス区分を属性値の文字列に変換する
     *
     * @param access エンドポイントのアクセス制御設定
     * @return "public" もしくは "authenticated"
     */
    private fun accessLabel(access: EndpointAccess): String = when (access) {
        is EndpointAccess.Public -> "public"
        is EndpointAccess.Authenticated -> "authenticated"
    }

    /**
     * 認証済みPrincipalを呼び出し元種別の文字列に変換する
     *
     * @param principal 認証済みPrincipal（公開エンドポイントで未認証の場合null）
     * @return "user" / "service" / "anonymous"
     */
    private fun callerTypeLabel(principal: Principal?): String = when (principal) {
        is Principal.User -> "user"
        is Principal.Service -> "service"
        null -> "anonymous"
    }

    /**
     * パスセグメントのマッチングを行う
     * リテラルは完全一致、パラメータは任意のセグメントにマッチする
     *
     * @param pattern コンパイル済みのパスパターン
     * @param segments リクエストのパスセグメント
     * @return 抽出されたパスパラメータのMap、マッチしない場合null
     */
    private fun matchPath(pattern: List<PathSegment>, segments: List<String>): Map<String, String>? {
        if (pattern.size != segments.size) return null

        // 末尾スラッシュのリクエストはKtorが空セグメントとして渡してくるため、
        // 空文字をパラメータにバインドせず404にフォールスルーさせる
        if (segments.any { it.isEmpty() }) return null

        val params = mutableMapOf<String, String>()
        for (index in pattern.indices) {
            when (val segment = pattern[index]) {
                is PathSegment.Literal -> {
                    if (segment.value != segments[index]) return null
                }
                is PathSegment.Param -> {
                    params[segment.name] = segments[index]
                }
            }
        }
        return params
    }

    /**
     * ルートの具体性を表すスコアを生成する
     * リテラル=1、パラメータ=0の文字列とすることで、
     * 辞書順比較が「左寄りのリテラルを優先」という直感的なルールになる
     *
     * @param pattern コンパイル済みのパスパターン
     * @return 比較可能な具体性スコア
     */
    private fun specificity(pattern: List<PathSegment>): String =
        pattern.joinToString("") { if (it is PathSegment.Literal) "1" else "0" }

    /**
     * 404レスポンスを返す
     */
    private suspend fun respondNotFound(call: ApplicationCall) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found", code = "not_found"))
    }
}
