package party.morino.mineauth.core.plugin.dispatch

import arrow.core.Either
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.opentelemetry.api.trace.Span
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.core.web.telemetry.OTEL_ROUTE_TEMPLATE
import party.morino.mineauth.core.web.telemetry.otelServerContext
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

        // 末尾スラッシュのリクエストはKtorが空セグメントとして渡してくるため、
        // 空文字をパラメータにバインドせず404にする（エンドポイントごとではなく1回だけ判定する）
        if (segments.any { it.isEmpty() }) {
            respondNotFound(call)
            return
        }

        // セグメント数が一致する候補だけを走査する（具体性の高い順に並んでいる）
        val candidates = table.candidates(segments.size)

        // HEADはGETエンドポイントで処理する（レスポンスボディはエンジン側で破棄される）
        val rawMethod = call.request.local.method.value
        val requestMethod = HTTP_METHODS[rawMethod] ?: if (rawMethod == "HEAD") HttpMethodType.GET else null

        // ホットパス: パスとメソッドの両方が一致する最初の候補を採用する
        // 候補は具体性の降順なので、最初の一致が最も具体的なルートになる
        // 例: /shops/mine と /shops/{id} が両方マッチしたら /shops/mine を選ぶ
        val hit = findEndpoint(candidates, segments, requestMethod)
        if (hit == null) {
            // コールドパス: 404 / 405 / OPTIONS の判別のためにパス一致のみのメソッド一覧を集める
            val allowed = candidates
                .filter { matchPath(it.pathSegments, segments) != null }
                .map { it.httpMethod.name }
                .distinct()
                .sorted()
            if (allowed.isEmpty()) {
                respondNotFound(call)
                return
            }
            call.response.headers.append(HttpHeaders.Allow, allowed.joinToString(", "))
            if (rawMethod == "OPTIONS") {
                // OPTIONSはAllowヘッダー付きの204で応答する（RFC 9110）
                call.respond(HttpStatusCode.NoContent)
            } else {
                // パスは合っているがメソッド違いは405 + Allowヘッダー
                call.respond(
                    HttpStatusCode.MethodNotAllowed,
                    ErrorResponse("Method not allowed", code = "method_not_allowed")
                )
            }
            return
        }
        val (endpoint, pathParams) = hit

        // OpenTelemetryのhttp.route（サーバースパン名）を実エンドポイントのテンプレートに補正する。
        // Ktorのルートは単一のキャッチオール（/api/v1/plugins/{namespace}/{path...}）のため、
        // ディスパッチャがマッチさせた実際のテンプレート（例: /api/v1/plugins/vault/shops/{id}）に
        // 上書きすることで、エンドポイント単位で識別・集計できるようにする。
        // NESTED_CONTROLLER(order=4)はサニタイザのCONTROLLER(order=3)より優先度が高いため、
        // ルート文字列の長短に関わらず必ずこの値が採用される。
        // サーバースパンのContextはハンドラのコルーチンでは失われることがあるため、
        // call.attributesに保存された確実なContextを使う（Context.current()に頼らない）
        val otelContext = call.otelServerContext()
        val routeTemplate = table.basePath + endpoint.path
        HttpServerRoute.update(
            otelContext,
            HttpServerRouteSource.NESTED_CONTROLLER,
            routeTemplate
        )
        // url.pathテンプレート化用に、より具体的なテンプレートで退避を上書きする
        call.attributes.put(OTEL_ROUTE_TEMPLATE, routeTemplate)
        // サーバースパンにプラグイン・ルートの識別属性を付与する（トップレベルHTTPスパンを検索可能にする）
        // トレーシング無効時はSpanがNoOpとなり、setAttributeは安全に無視される
        Span.fromContext(otelContext).apply {
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
        Span.fromContext(otelContext).setAttribute(TelemetryAttributes.CALLER_TYPE, callerTypeLabel(principal))

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
     * パスとHTTPメソッドの両方が一致する最初のエンドポイントを探す
     *
     * @param candidates 具体性の降順に並んだ候補（セグメント数は一致済み）
     * @param segments リクエストのパスセグメント
     * @param requestMethod リクエストのHTTPメソッド（未対応メソッドはnull）
     * @return 一致したエンドポイントと抽出済みパスパラメータ、なければnull
     */
    private fun findEndpoint(
        candidates: List<EndpointMetadata>,
        segments: List<String>,
        requestMethod: HttpMethodType?
    ): Pair<EndpointMetadata, Map<String, String>>? {
        if (requestMethod == null) return null
        for (endpoint in candidates) {
            // メソッド比較は安価なので先に行い、パス照合の回数を減らす
            if (endpoint.httpMethod != requestMethod) continue
            val params = matchPath(endpoint.pathSegments, segments) ?: continue
            return endpoint to params
        }
        return null
    }

    /**
     * パスセグメントのマッチングを行う
     * リテラルは完全一致、パラメータは任意のセグメントにマッチする
     *
     * @param pattern コンパイル済みのパスパターン（セグメント数はリクエストと一致している前提）
     * @param segments リクエストのパスセグメント（空セグメントは含まれない前提）
     * @return 抽出されたパスパラメータのMap、マッチしない場合null
     */
    private fun matchPath(pattern: List<PathSegment>, segments: List<String>): Map<String, String>? {
        if (pattern.size != segments.size) return null

        // パラメータを含まないルートではMapを割り当てない
        var params: MutableMap<String, String>? = null
        for (index in pattern.indices) {
            when (val segment = pattern[index]) {
                is PathSegment.Literal -> {
                    if (segment.value != segments[index]) return null
                }
                is PathSegment.Param -> {
                    val map = params ?: HashMap<String, String>(4).also { params = it }
                    map[segment.name] = segments[index]
                }
            }
        }
        return params ?: emptyMap()
    }

    /**
     * 404レスポンスを返す
     */
    private suspend fun respondNotFound(call: ApplicationCall) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Not found", code = "not_found"))
    }

    companion object {
        // HTTPメソッド名 -> 内部列挙値（リクエストごとの線形探索を避ける）
        private val HTTP_METHODS: Map<String, HttpMethodType> = HttpMethodType.entries.associateBy { it.name }
    }
}
