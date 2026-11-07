package party.morino.mineauth.core.web

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * アクセスログ（CallLogging）の設定のテスト
 * OpenTelemetry SDKに触れないよう、WebServerのモジュールではなく同じ設定だけを組み込んで確認する
 */
class AccessLogTest {

    @Test
    @DisplayName("Rate limited responses are left out of the access log")
    fun skipsRateLimitedResponses() = testApplication {
        // グローバルなログ設定に影響されないよう、独立したコンテキストのロガーに記録する
        val loggerContext = LoggerContext()
        val accessLogger = loggerContext.getLogger("AccessLogTest").apply { level = Level.INFO }
        val appender = ListAppender<ILoggingEvent>().apply {
            context = loggerContext
            start()
        }
        accessLogger.addAppender(appender)

        application {
            install(CallLogging) {
                configureAccessLog()
                logger = accessLogger
            }
            routing {
                get("/limited") { call.respond(HttpStatusCode.TooManyRequests) }
                get("/busy") { call.respond(HttpStatusCode.ServiceUnavailable) }
                get("/health") { call.respond(HttpStatusCode.OK) }
            }
        }

        client.get("/limited")
        client.get("/health")
        client.get("/busy")

        // サーバー側のスレッドが追記するので、AppenderBaseと同じロックの中で読む
        fun logged(): List<String> = synchronized(appender) { appender.list.map { it.formattedMessage } }

        // ログはレスポンスの送信後に出力されるので、最後のリクエストが記録されるまで待つ（仮想時間に依らず実時間で待つ）
        val deadline = System.currentTimeMillis() + 5_000
        while (logged().none { "/busy" in it } && System.currentTimeMillis() < deadline) delay(10)

        // ヘルスチェックと429は記録せず、503は記録する
        val messages = logged()
        assertEquals(1, messages.size, messages.toString())
        assertTrue("/busy" in messages.single())
    }
}
