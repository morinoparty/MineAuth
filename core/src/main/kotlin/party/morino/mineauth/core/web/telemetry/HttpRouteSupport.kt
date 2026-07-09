package party.morino.mineauth.core.web.telemetry

import io.ktor.server.application.Application
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.opentelemetry.context.Context
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource

/**
 * OpenTelemetryの`http.route`（HTTPサーバースパン名）を補正するためのヘルパー
 *
 * 背景:
 * KtorServerTelemetryは`RoutingRoot.RoutingCallStarted`で
 * `matchedRoute.parent.toString()`を`http.route`として設定する。
 * このルート文字列は`authenticate { ... }`で包まれたルートの場合、
 * 認証セレクタの文字列（例: `(authenticate user-oauth-token, service-oauth-token)`）を
 * そのまま含んでしまい、スパン名が
 * `/(authenticate user-oauth-token, service-oauth-token)/api/v1/plugins/{namespace}`
 * のように汚染される。
 *
 * ここでは認証セレクタ由来のセグメントを除去し、素直なルートテンプレートに戻す。
 */

// 認証ルートセレクタの`toString()`形式（例: "(authenticate user-oauth-token, service-oauth-token)"）
// プロバイダ名にスラッシュは含まれないため、1つのパスセグメント全体がこの形にマッチする
private val AUTHENTICATE_SELECTOR = Regex("""\(authenticate .*\)""")

/**
 * ルート文字列から認証セレクタ由来のセグメントを取り除く
 *
 * 例: `/(authenticate user-oauth-token, service-oauth-token)/api/v1/plugins/{namespace}`
 *   → `/api/v1/plugins/{namespace}`
 *
 * Ktor本体が生成したルート文字列をそのまま入力に取り、既知の汚染セグメントのみを
 * 外科的に除去することで、Ktorのルート表記そのものは維持する。
 *
 * @param route Ktorが生成したルート文字列
 * @return 認証セレクタを除去したルート文字列
 */
fun sanitizeAuthRoute(route: String): String =
    route.split("/")
        .filterNot { AUTHENTICATE_SELECTOR.matches(it) }
        .joinToString("/")

/**
 * すべてのルートに対して`http.route`から認証セレクタを除去するサニタイザを登録する
 *
 * KtorServerTelemetryと同じ`RoutingRoot.RoutingCallStarted`を購読し、
 * より優先度の高いソース（CONTROLLER）でルートを上書きする。
 * `CONTROLLER`(order=3)はライブラリが使う`SERVER`(order=2)より優先度が高いため、
 * 購読の実行順序に関わらず必ずこちらの値が採用される。
 *
 * トレーシングが無効の場合、Context上にHttpRouteStateが存在しないため
 * `HttpServerRoute.update`は安全にno-opとなる。
 *
 * @receiver 対象のKtor Application
 */
fun Application.installAuthRouteSanitizer() {
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        // マッチしたルートの親（メソッドセレクタを除いた部分）を起点にサニタイズする
        val parent: RoutingNode = call.route.parent ?: return@subscribe
        HttpServerRoute.update(
            Context.current(),
            HttpServerRouteSource.CONTROLLER,
            sanitizeAuthRoute(parent.toString())
        )
    }
}
