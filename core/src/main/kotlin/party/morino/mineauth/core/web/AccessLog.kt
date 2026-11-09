package party.morino.mineauth.core.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.calllogging.CallLoggingConfig
import io.ktor.server.plugins.calllogging.processingTimeMillis
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level

/**
 * HTTPリクエスト/レスポンスのアクセスログ（CallLogging）を設定する
 * WebServerとテストで同じ設定を使うため、設定の内容をここにまとめる
 */
internal fun CallLoggingConfig.configureAccessLog() {
    level = Level.INFO
    // リクエスト情報をログに出力
    format { call ->
        val status = call.response.status()
        val httpMethod = call.request.httpMethod.value
        val path = call.request.path()
        val duration = call.processingTimeMillis()
        "HTTP $httpMethod $path - $status [${duration}ms]"
    }
    // CallLoggingは複数のfilterのいずれかがtrueなら記録するので、除外の条件は1つのfilterにまとめる
    filter { call -> call.isAccessLogged() }
}

/**
 * アクセスログに記録するリクエストかどうかを判定する
 * レスポンスの送信後に呼ばれるので、ステータスで判定できる
 *
 * @return 記録する場合はtrue
 */
private fun ApplicationCall.isAccessLogged(): Boolean {
    val path = request.path()
    // ヘルスチェックやアセット、メトリクスへのリクエストは除外
    if (path.startsWith("/assets") || path == "/health" || path == "/metrics") return false
    // 流量制限の429は1件ずつ記録しない（大量のリクエストがそのままログの洪水になる。件数はトレースのスパンで確認する）
    return response.status() != HttpStatusCode.TooManyRequests
}
