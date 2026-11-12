package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.utils.Argon2Utils
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * クライアント認証がArgon2Gateを通ることのルートテスト
 * 許可を別のスレッドで持ち続け、空きが無い時の応答を確認する（実際のArgon2を並列に走らせない）
 */
class Argon2GateRouteTest : OAuthRouteTestBase() {
    // 許可を待つ時間を短くし、テストを待たせない
    private val gate = Argon2Gate(permits = 1, acquireTimeout = 50.milliseconds)

    /**
     * テスト用のゲートと、有効な流量制限を登録する
     *
     * @param testGate 登録するゲート
     */
    private fun useTestGate(testGate: Argon2Gate = gate) {
        val limits = OAuthRateLimits(RateLimitConfig(), emptyList(), Logger.getLogger("Argon2GateRouteTest"))
        loadKoinModules(
            module {
                single { testGate }
                single { limits }
            }
        )
    }

    /**
     * 指定した接続元の検証を別のスレッドで実行したまま止め、その間に処理を実行する
     *
     * @param testGate 対象のゲート
     * @param source 検証を要求する接続元
     * @param verifications 止めておく検証の数
     * @param block 検証を止めている間に実行する処理
     */
    private suspend fun <T> whileHolding(
        testGate: Argon2Gate,
        source: ClientIpKey,
        verifications: Int,
        block: suspend () -> T
    ): T {
        val holding = CountDownLatch(verifications)
        val release = CountDownLatch(1)
        val holders = List(verifications) {
            thread {
                runBlocking {
                    testGate.withPermit(source) {
                        holding.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            }
        }
        holding.await(30, TimeUnit.SECONDS)
        try {
            return block()
        } finally {
            release.countDown()
            holders.forEach { it.join() }
        }
    }

    /**
     * ゲートの唯一の許可を、テストクライアントとは別の接続元が持ったまま処理を実行する
     *
     * @param block 許可を持っている間に実行する処理
     */
    private suspend fun <T> whileGateIsFull(block: suspend () -> T): T =
        whileHolding(gate, ClientIpKey("198.51.100.1"), verifications = 1, block)

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        client: OAuthClientCreationResult,
        secret: String = client.clientSecret!!
    ): HttpResponse =
        this.client.submitForm(
            url = path,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("token", "not-a-jwt")
                append("client_id", client.client.clientId)
                append("client_secret", secret)
            }
        )

    private suspend fun assertBusy(response: HttpResponse) {
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("temporarily_unavailable", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    @Test
    @DisplayName("A full gate answers 503 at every client authentication endpoint without hashing")
    fun fullGateAnswers503WithoutHashing() {
        // MockKの解除はスレッドローカルで管理されるため、コルーチンの外（同じスレッド）で設定と解除を行う
        mockkObject(Argon2Utils)
        try {
            oauthTestApplication {
                useTestGate()
                val resourceServer = registerResourceServer()

                whileGateIsFull {
                    listOf("/oauth2/token", "/oauth2/revoke", "/oauth2/introspect").forEach { path ->
                        assertBusy(post(path, resourceServer))
                    }
                }
            }
            verify(exactly = 0) { Argon2Utils.verifySecret(any(), any()) }
        } finally {
            unmockkObject(Argon2Utils)
        }
    }
}
