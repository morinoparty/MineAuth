package party.morino.mineauth.core.web.ratelimit

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.RoutingCall
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import party.morino.mineauth.core.utils.Argon2GateBusy
import party.morino.mineauth.core.web.router.auth.oauth.ClientAuthFailure
import party.morino.mineauth.core.web.router.auth.oauth.ClientAuthFailureKind
import party.morino.mineauth.core.web.router.auth.oauth.OAuthErrorCode
import party.morino.mineauth.core.web.router.auth.oauth.respondOAuthError
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes

/**
 * リクエストのクライアントを解決する
 *
 * @param call 現在のリクエスト
 * @return クライアントのキー
 */
fun OAuthRateLimits.clientIpOf(call: RoutingCall): ClientIpKey =
    resolveClientIp(call.request.local.remoteAddress, call.request.headers.forwardedFor())

/**
 * X-Forwarded-Forの値を取得する
 * プロキシが追記した行を読み落とすと、クライアントが書いた先頭の行だけを信じて偽装されるので、全ての行を順に連結する
 * （RFC 9110 Section 5.3: 同名のヘッダーの複数行は、カンマで連結した1行と同じ意味を持つ）
 *
 * @return 全ての行を連結した値。ヘッダーが無い場合はnull
 */
internal fun Headers.forwardedFor(): String? = getAll(HttpHeaders.XForwardedFor)?.joinToString(",")

/**
 * 流量制限で止めたことをRetry-Afterとテレメトリに記録する
 * WARNINGは出力しない（リクエストごとに出力すると、大量のリクエストがそのままログの洪水になる）
 *
 * @param limited 制限の内容
 */
fun RoutingCall.markRateLimited(limited: RateLimited) = markTemporarilyUnavailable(limited.retryAfterSeconds)

/**
 * Argon2の検証が混雑していて照合しなかったことをRetry-Afterとテレメトリに記録する
 */
fun RoutingCall.markVerifierBusy() = markTemporarilyUnavailable(Argon2GateBusy.RETRY_AFTER_SECONDS)

/**
 * 一時的に処理しなかったことをRetry-Afterとテレメトリに記録する
 *
 * @param retryAfterSeconds 再試行できるまでの秒数
 */
private fun RoutingCall.markTemporarilyUnavailable(retryAfterSeconds: Long) {
    response.header(HttpHeaders.RetryAfter, retryAfterSeconds.toString())
    // respondOAuthErrorと同じくエラーコードをスパンに記録する。トレーシング無効時はNoOp Spanになるため安全
    Span.current()
        .setAttribute(TelemetryAttributes.OAUTH_ERROR_CODE, OAuthErrorCode.TEMPORARILY_UNAVAILABLE.code)
        .setStatus(StatusCode.ERROR)
}

/**
 * JSONを返すエンドポイントで、流量制限のエラーレスポンスを返す
 * respondOAuthErrorはリクエストごとにWARNINGを出力するので、制限中の経路ではこちらを使う。
 * 429の応答はアクセスログ（CallLogging）からも除外している
 *
 * @param limited 制限の内容
 */
suspend fun RoutingCall.respondRateLimited(limited: RateLimited) {
    markRateLimited(limited)
    respondTemporarilyUnavailable(HttpStatusCode.TooManyRequests, "Too many requests. Retry later")
}

/**
 * JSONを返すエンドポイントで、Argon2の検証が混雑していることを503で返す
 * 認証情報は評価していないので、クライアントはRetry-Afterの後にそのまま再試行してよい。
 * 大量のリクエストで混雑している時にWARNINGが溢れないよう、respondOAuthErrorは使わない
 */
suspend fun RoutingCall.respondVerifierBusy() {
    markVerifierBusy()
    respondTemporarilyUnavailable(HttpStatusCode.ServiceUnavailable, "The server is busy. Retry later")
}

/**
 * temporarily_unavailableのエラーレスポンスを返す
 *
 * @param status HTTPステータス
 * @param description エラーの詳細説明
 */
private suspend fun RoutingCall.respondTemporarilyUnavailable(status: HttpStatusCode, description: String) {
    // RFC 6749 Section 5.2: エラーレスポンスにはキャッシュ禁止ヘッダーを付与
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(HttpHeaders.Pragma, "no-cache")
    respond(status, OAuthErrorCode.TEMPORARILY_UNAVAILABLE.toResponse(description))
}

/**
 * クライアント認証の失敗を応答する
 * 混雑で照合しなかった場合は認証の失敗ではないので、invalid_clientではなく503で再試行を促す
 *
 * @param failure クライアント認証の失敗情報
 */
suspend fun RoutingCall.respondClientAuthFailure(failure: ClientAuthFailure) {
    if (failure.kind == ClientAuthFailureKind.VERIFIER_BUSY) {
        respondVerifierBusy()
    } else {
        respondOAuthError(failure.errorCode, failure.message)
    }
}
