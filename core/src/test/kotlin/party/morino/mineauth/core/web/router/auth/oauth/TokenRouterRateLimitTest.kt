package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals

/**
 * トークンエンドポイントの流量制限のルートテスト
 * テストホストの接続元は "localhost" 固定なので、全てのリクエストが同じクライアントとして数えられる
 * 回数のバケットはクライアント認証に成功してから数えるので、登録済みのクライアントで確認する
 */
class TokenRouterRateLimitTest : OAuthRouteTestBase() {
    private var now = 0L

    /** 流量制限を指定した設定に置き換える */
    private fun useRateLimits(config: RateLimitConfig) {
        val limits = OAuthRateLimits(config, emptyList(), Logger.getLogger("TokenRouterRateLimitTest"), clock = { now })
        loadKoinModules(module { single { limits } })
    }

    private suspend fun ApplicationTestBuilder.refreshGrant(clientId: String, clientSecret: String? = null): HttpResponse =
        client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("client_id", clientId)
                clientSecret?.let { append("client_secret", it) }
            }
        )

    private suspend fun ApplicationTestBuilder.codeGrant(clientId: String): HttpResponse =
        client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("client_id", clientId)
                append("code", "unknown")
                append("redirect_uri", "https://rp.example.com/callback")
                append("code_verifier", "a".repeat(43))
            }
        )

    private suspend fun assertLimited(response: HttpResponse, retryAfterSeconds: Int) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals(retryAfterSeconds.toString(), response.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("temporarily_unavailable", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    // 認証を通過したリクエストは、refresh_tokenが無いのでinvalid_requestになる
    private fun assertAuthenticated(response: HttpResponse) = assertEquals(HttpStatusCode.BadRequest, response.status)

    @Test
    @DisplayName("A confidential client spends only its own bucket and only after authentication")
    fun confidentialClientIsCountedAfterAuthentication() = oauthTestApplication {
        useRateLimits(RateLimitConfig(tokenRequestsPerMinutePerIp = 1, tokenRequestsPerMinutePerClient = 2))
        val confidential = registerClient(ClientType.CONFIDENTIAL)
        val clientId = confidential.client.clientId

        // シークレットを知らない第三者の失敗はバケットを消費しない
        repeat(3) { assertEquals(HttpStatusCode.Unauthorized, refreshGrant(clientId, "wrong").status) }

        // 認証済みのリクエストはIPのバケット（1回）を使わず、client_idのバケット（2回）で数える
        repeat(2) { assertAuthenticated(refreshGrant(clientId, confidential.clientSecret)) }
        assertLimited(refreshGrant(clientId, confidential.clientSecret), retryAfterSeconds = 30)
    }

    @Test
    @DisplayName("Client authentication backoff stops authentication")
    fun backoffStopsAuthentication() {
        // MockKの解除はスレッドローカルで管理されるため、コルーチンの外（同じスレッド）で設定と解除を行う
        mockkObject(OAuthValidation)
        try {
            oauthTestApplication {
                useRateLimits(RateLimitConfig(tokenRequestsPerMinutePerIp = 0, tokenRequestsPerMinutePerClient = 0))
                val clientId = UUID.randomUUID().toString()

                // 猶予の10回と、ブロックを始める11回目までは認証を実行する
                repeat(11) { assertEquals(HttpStatusCode.Unauthorized, refreshGrant(clientId).status) }
                assertLimited(refreshGrant(clientId), retryAfterSeconds = 1)
                assertLimited(codeGrant(clientId), retryAfterSeconds = 1)

                // ブロックが解けると再び認証を実行する
                now += 1_000
                assertEquals(HttpStatusCode.Unauthorized, refreshGrant(clientId).status)
            }
            coVerify(exactly = 12) { OAuthValidation.authenticateClient(any(), any()) }
        } finally {
            unmockkObject(OAuthValidation)
        }
    }
}
