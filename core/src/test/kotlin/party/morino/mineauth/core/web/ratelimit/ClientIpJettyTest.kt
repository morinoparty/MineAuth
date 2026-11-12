package party.morino.mineauth.core.web.ratelimit

import arrow.core.right
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.tokenRouter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 実際のJettyでの接続元とX-Forwarded-Forの扱いのテスト
 *
 * テストホストの接続元は "localhost" 固定で信頼できるプロキシを再現できないので、
 * 127.0.0.1から接続してプロキシ経由のリクエストを再現する。バケットは1回/分なので、
 * 2回目が429になれば1回目と同じクライアントとして数えられたことが分かる
 *
 * IPごとのバケットはPublicクライアントの認証に成功してから数えるので、クライアント認証はモックで常に成功させる
 */
class ClientIpJettyTest {
    private val logHandler = RecordingLogHandler()

    // 同じヘッダーを複数行で送るため、値を連結してしまうKtorのクライアントではなくJDKのクライアントを使う
    private val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private val allowedStatus = 400
    private val limitedStatus = 429

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("ClientIpJettyTest")
        // MockKの解除はスレッドローカルで管理されるため、JUnitのスレッドで設定と解除を行う
        mockkObject(OAuthValidation)
        coEvery { OAuthValidation.authenticateClient(any(), any()) } returns
            ClientData.PublicClientData("public-client", "Public", emptyList()).right()
        startKoin {
            modules(
                module {
                    single<MineAuth> { plugin }
                    single { rateLimits(trustedProxies = listOf("127.0.0.0/8", "::1/128")) }
                }
            )
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OAuthValidation)
        stopKoin()
    }

    private fun rateLimits(trustedProxies: List<String>) = OAuthRateLimits(
        RateLimitConfig(tokenRequestsPerMinutePerIp = 1, tokenRequestsPerMinutePerClient = 0),
        trustedProxies.map { assertNotNull(CidrRange.parse(it)) },
        logHandler.newLogger()
    )

    private fun withServer(block: (port: Int) -> Unit) {
        val server = embeddedServer(Jetty, port = 0) {
            install(ContentNegotiation) { json(Json) }
            routing {
                route("/oauth2") {
                    tokenRouter()
                }
            }
        }
        server.start(wait = false)
        try {
            block(runBlocking { server.engine.resolvedConnectors().first().port })
        } finally {
            server.stop(0, 0, TimeUnit.SECONDS)
        }
    }

    /**
     * 127.0.0.1からPublicクライアントとしてトークンエンドポイントへ送信し、ステータスを返す
     * refresh_tokenが無いので、制限に掛からなければ400になる
     *
     * @param port サーバーのポート
     * @param forwardedForLines X-Forwarded-Forの各行
     * @return HTTPステータス
     */
    private fun post(port: Int, vararg forwardedForLines: String): Int {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/oauth2/token"))
            .header(HttpHeaders.ContentType, "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("grant_type=refresh_token&client_id=public-client"))
        forwardedForLines.forEach { request.header(HttpHeaders.XForwardedFor, it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    @Test
    @DisplayName("Counts forwarded clients separately behind a trusted proxy")
    fun countsForwardedClientsSeparately() = withServer { port ->
        assertEquals(allowedStatus, post(port, "203.0.113.7"))
        assertEquals(limitedStatus, post(port, "203.0.113.7"))
        assertEquals(allowedStatus, post(port, "203.0.113.8"))
    }

    @Test
    @DisplayName("Reads every X-Forwarded-For header line")
    fun readsEveryHeaderLine() = withServer { port ->
        assertEquals(allowedStatus, post(port, "203.0.113.7"))
        // 先頭の行はクライアントが書いた値で、プロキシは2行目を追記している
        assertEquals(limitedStatus, post(port, "198.51.100.1", "203.0.113.7"))
    }
}
