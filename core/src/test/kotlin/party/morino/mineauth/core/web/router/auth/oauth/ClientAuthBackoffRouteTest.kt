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
 * 失効・イントロスペクションのエンドポイントのクライアント認証バックオフのルートテスト
 * トークンエンドポイントと同じArgon2の検証を実行するので、迂回路にならないことを確認する
 *
 * 失敗はシークレットの検証を伴わない未登録のclient_idで積み上げ、ハッシュ計算の時間を掛けない
 */
class ClientAuthBackoffRouteTest : OAuthRouteTestBase() {
    private var now = 0L

    // ブロックが始まるまでの失敗回数（猶予の10回と、ブロックを始める11回目）
    private val failuresUntilBlocked = 11

    /** 流量制限を指定した設定に置き換える */
    private fun useRateLimits(config: RateLimitConfig = RateLimitConfig()) {
        val limits = OAuthRateLimits(config, emptyList(), Logger.getLogger("ClientAuthBackoffRouteTest"), clock = { now })
        loadKoinModules(module { single { limits } })
    }

    private suspend fun ApplicationTestBuilder.postToken(path: String, clientId: String, clientSecret: String): HttpResponse =
        client.submitForm(
            url = path,
            formParameters = parameters {
                append("token", "not-a-jwt")
                append("client_id", clientId)
                append("client_secret", clientSecret)
            }
        )

    /** ブロックされるまで認証を失敗させる */
    private suspend fun ApplicationTestBuilder.failUntilBlocked(path: String) {
        repeat(failuresUntilBlocked) {
            val response = postToken(path, UUID.randomUUID().toString(), "wrong")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    private suspend fun assertBlocked(response: HttpResponse) {
        assertEquals(HttpStatusCode.TooManyRequests, response.status)
        assertEquals("1", response.headers[HttpHeaders.RetryAfter])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
        assertEquals("temporarily_unavailable", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    /**
     * ブロック中は正しいシークレットでも評価せず、解除後は通ることを確認する
     *
     * @param path 対象のエンドポイント
     */
    private fun assertBackoffStopsVerification(path: String) {
        // MockKの解除はスレッドローカルで管理されるため、コルーチンの外（同じスレッド）で設定と解除を行う
        mockkObject(OAuthValidation)
        try {
            oauthTestApplication {
                useRateLimits()
                // イントロスペクションは権限も必要なので、ブロック解除後の200まで確認できるクライアントを使う
                val confidential = registerResourceServer()
                failUntilBlocked(path)

                assertBlocked(postToken(path, confidential.client.clientId, confidential.clientSecret!!))

                now += 1_000
                assertEquals(HttpStatusCode.OK, postToken(path, confidential.client.clientId, confidential.clientSecret!!).status)
            }
            // ブロック中の1回分は認証が実行されない
            coVerify(exactly = failuresUntilBlocked + 1) { OAuthValidation.authenticateConfidentialClient(any(), any()) }
        } finally {
            unmockkObject(OAuthValidation)
        }
    }

    @Test
    @DisplayName("Revoke does not verify the secret during the backoff")
    fun revokeBackoffStopsVerification() = assertBackoffStopsVerification("/oauth2/revoke")

    @Test
    @DisplayName("Introspect does not verify the secret during the backoff")
    fun introspectBackoffStopsVerification() = assertBackoffStopsVerification("/oauth2/introspect")

    @Test
    @DisplayName("Failures at revoke also block the token endpoint and introspect")
    fun backoffIsShared() = oauthTestApplication {
        useRateLimits(RateLimitConfig(tokenRequestsPerMinutePerIp = 0, tokenRequestsPerMinutePerClient = 0))
        failUntilBlocked("/oauth2/revoke")

        val clientId = UUID.randomUUID().toString()
        assertBlocked(postToken("/oauth2/introspect", clientId, "wrong"))
        assertBlocked(
            client.submitForm(
                url = "/oauth2/token",
                formParameters = parameters {
                    append("grant_type", "refresh_token")
                    append("client_id", clientId)
                }
            )
        )
    }

    @Test
    @DisplayName("Requests that verify no secret do not count toward the backoff")
    fun unverifiedRequestsAreNotCounted() = oauthTestApplication {
        useRateLimits()
        val publicClient = registerClient(ClientType.PUBLIC)
        val resourceServer = registerResourceServer()

        // ログアウト時にrevokeを呼ぶPublicクライアントは、シークレットを送れないので毎回invalid_clientになる
        // 何も照合していないので、猶予の10回を超えても利用者のIPをブロックしない
        repeat(failuresUntilBlocked + 2) {
            val response = client.submitForm(
                url = "/oauth2/revoke",
                formParameters = parameters {
                    append("token", "not-a-jwt")
                    append("client_id", publicClient.client.clientId)
                }
            )
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
        // Publicクライアントがシークレットを送っても、種別が違うだけで照合はしないので数えない
        repeat(failuresUntilBlocked + 2) {
            assertEquals(HttpStatusCode.Unauthorized, postToken("/oauth2/revoke", publicClient.client.clientId, "any").status)
        }

        assertEquals(HttpStatusCode.OK, postToken("/oauth2/introspect", resourceServer.client.clientId, resourceServer.clientSecret!!).status)
    }

    @Test
    @DisplayName("Revoke and introspect do not spend the request buckets")
    fun doNotSpendRequestBuckets() = oauthTestApplication {
        useRateLimits(RateLimitConfig(tokenRequestsPerMinutePerIp = 1, tokenRequestsPerMinutePerClient = 1))
        val resourceServer = registerResourceServer()

        // リソースサーバーは正当に毎秒何度も問い合わせるので、回数では制限しない
        repeat(3) {
            assertEquals(
                HttpStatusCode.OK,
                postToken("/oauth2/introspect", resourceServer.client.clientId, resourceServer.clientSecret!!).status
            )
            assertEquals(
                HttpStatusCode.OK,
                postToken("/oauth2/revoke", resourceServer.client.clientId, resourceServer.clientSecret!!).status
            )
        }
    }

    @Test
    @DisplayName("Disabled config allows every failure through")
    fun disabledAllowsEverything() = oauthTestApplication {
        useRateLimits(RateLimitConfig(enabled = false))

        repeat(failuresUntilBlocked + 2) {
            assertEquals(HttpStatusCode.Unauthorized, postToken("/oauth2/revoke", UUID.randomUUID().toString(), "wrong").status)
        }
    }
}
