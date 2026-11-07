package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizeRouter.authorizeRouter
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * パスワードログインの流量制限のルートテスト
 * テストホストの接続元は "localhost" 固定なので、全てのリクエストが同じクライアントとして数えられる
 */
class AuthorizeRouterRateLimitTest {
    private var now = 0L
    private val logHandler = RecordingLogHandler()
    private val redirectUri = "https://app.example.com/callback"
    private val success = AuthenticationResult.Success(UUID.fromString("00000000-0000-0000-0000-000000000001"))
    private val failure = AuthenticationResult.Failed(AuthenticationError.INVALID_PASSWORD)

    // ブロックが始まるまでの失敗回数（IPごとの猶予の5回と、ブロックを始める6回目）
    private val failuresUntilBlocked = 6

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns logHandler.newLogger()
        startKoin {
            modules(module { single { plugin } })
        }
        useRateLimits(RateLimitConfig())

        val client = ClientData.PublicClientData("client", "Sample", listOf(redirectUri))
        mockkObject(OAuthService)
        every { OAuthService.getClientData(any()) } answers { client.takeIf { it.clientId == firstArg<String>() } }
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns failure
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OAuthService)
        stopKoin()
        OAuthRouter.authorizedData.clear()
    }

    /** 流量制限を指定した設定に置き換える */
    private fun useRateLimits(config: RateLimitConfig): OAuthRateLimits {
        val limits = OAuthRateLimits(config, emptyList(), logHandler.newLogger(), clock = { now })
        loadKoinModules(module { single { limits } })
        return limits
    }

    /** 認可エンドポイントだけを持つアプリケーションで、リダイレクトを追わないクライアントを使ってテストする */
    private fun withClient(block: suspend (HttpClient) -> Unit) {
        testApplication {
            application {
                install(ContentNegotiation) { json(Json) }
                routing {
                    route("/oauth2") {
                        authorizeRouter()
                    }
                }
            }
            block(createClient { followRedirects = false })
        }
    }

    /** ログインを送信する。overridesで有効なパラメータの一部を上書きできる（nullはキーごと削除する） */
    private suspend fun HttpClient.login(vararg overrides: Pair<String, String?>): HttpResponse {
        val defaults = mapOf(
            "response_type" to "code",
            "client_id" to "client",
            "redirect_uri" to redirectUri,
            "scope" to "openid",
            "state" to "state-1",
            "code_challenge" to "challenge",
            "code_challenge_method" to "S256",
            "username" to "Steve",
            "password" to "secret",
        )
        return submitForm(
            "/oauth2/authorize",
            Parameters.build { (defaults + overrides).forEach { (key, value) -> value?.let { append(key, it) } } },
        )
    }

    private fun HttpResponse.redirectParameters(): Parameters {
        assertEquals(HttpStatusCode.Found, status)
        return Url(assertNotNull(headers[HttpHeaders.Location])).parameters
    }

    @Test
    @DisplayName("A blocked login is not evaluated and redirects with temporarily_unavailable")
    fun blockedLoginIsNotEvaluated() = withClient { client ->
        repeat(failuresUntilBlocked) { assertEquals("access_denied", client.login().redirectParameters()["error"]) }

        // 正しいパスワードでも、ブロック中は評価しない
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns success
        val blocked = client.login()

        val redirect = blocked.redirectParameters()
        assertEquals("temporarily_unavailable", redirect["error"])
        assertEquals("state-1", redirect["state"])
        assertEquals("1", blocked.headers[HttpHeaders.RetryAfter])
        coVerify(exactly = failuresUntilBlocked) { OAuthService.authenticateUser(any(), any(), any()) }
        // ログの洪水にならないよう、ブロックをログに出力しない
        assertEquals(emptyList(), logHandler.warnings)

        // ブロックが解けると評価される
        now += 1_000
        assertNotNull(client.login().redirectParameters()["code"])
    }

    @Test
    @DisplayName("The attempt bucket limits login submissions per IP")
    fun attemptBucketLimitsLogins() = withClient { client ->
        useRateLimits(RateLimitConfig(loginAttemptsPerMinutePerIp = 1))
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns success

        assertNotNull(client.login().redirectParameters()["code"])
        val limited = client.login()

        assertEquals("temporarily_unavailable", limited.redirectParameters()["error"])
        assertEquals("60", limited.headers[HttpHeaders.RetryAfter])
        // Argon2Gateの接続元ごとの枠は、流量制限と同じクライアントのキーで割り当てる
        coVerify(exactly = 1) { OAuthService.authenticateUser(any(), any(), ClientIpKey("localhost")) }
    }

    @Test
    @DisplayName("The username block holds new addresses but not one that logged in before")
    fun usernameBlockSkipsAddressThatLoggedIn() = withClient { client ->
        val limits = useRateLimits(RateLimitConfig())
        // 他の多数の接続元からの失敗で、ユーザー名をブロックさせる
        val blockUsername = { repeat(21) { limits.recordLoginFailure(ClientIpKey("2001:db8:0:$it::"), "steve") } }

        blockUsername()
        assertEquals("temporarily_unavailable", client.login().redirectParameters()["error"])

        // 本人がこの接続元からログインに成功した後は、ブロックが更新されても評価される
        now += 1_000
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns success
        assertNotNull(client.login().redirectParameters()["code"])
        blockUsername()
        assertNotNull(client.login().redirectParameters()["code"])
    }

    @Test
    @DisplayName("A successful login does not reset the failures of the IP")
    fun successDoesNotResetIpFailures() = withClient { client ->
        repeat(failuresUntilBlocked - 1) { client.login() }

        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns success
        assertNotNull(client.login().redirectParameters()["code"])
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns failure
        assertEquals("access_denied", client.login().redirectParameters()["error"])

        assertEquals("temporarily_unavailable", client.login().redirectParameters()["error"])
    }

    @Test
    @DisplayName("Requests that fail validation are not counted")
    fun invalidRequestsAreNotCounted() = withClient { client ->
        useRateLimits(RateLimitConfig(loginAttemptsPerMinutePerIp = 1))
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns success

        // redirect_uriやclient_idが不正な送信と、認証情報が揃っていない送信はバケットを消費しない
        assertEquals(HttpStatusCode.BadRequest, client.login("redirect_uri" to "https://evil.example").status)
        assertEquals(HttpStatusCode.BadRequest, client.login("client_id" to "unknown").status)
        assertEquals("invalid_request", client.login("username" to null).redirectParameters()["error"])

        assertNotNull(client.login().redirectParameters()["code"])
    }

    @Test
    @DisplayName("A busy verifier redirects with temporarily_unavailable and is not counted")
    fun busyVerifierIsNotCounted() = withClient { client ->
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns AuthenticationResult.VerifierBusy

        // IPごとの猶予の5回を超えて断られても、ログインの失敗としては数えない
        repeat(failuresUntilBlocked + 2) {
            val busy = client.login()
            val redirect = busy.redirectParameters()
            assertEquals("temporarily_unavailable", redirect["error"])
            assertEquals("state-1", redirect["state"])
            assertEquals("1", busy.headers[HttpHeaders.RetryAfter])
        }

        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns success
        assertNotNull(client.login().redirectParameters()["code"])
    }

    @Test
    @DisplayName("Disabled config allows everything")
    fun disabledAllowsEverything() = withClient { client ->
        useRateLimits(RateLimitConfig(enabled = false, loginAttemptsPerMinutePerIp = 1))

        repeat(failuresUntilBlocked + 2) { assertEquals("access_denied", client.login().redirectParameters()["error"]) }

        coVerify(exactly = failuresUntilBlocked + 2) { OAuthService.authenticateUser(any(), any(), any()) }
    }
}
