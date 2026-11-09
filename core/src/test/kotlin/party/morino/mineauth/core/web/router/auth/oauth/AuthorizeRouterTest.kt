package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.contentType
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
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.OAuthConfigData
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.page.AuthorizePageRenderer
import party.morino.mineauth.core.web.ratelimit.RateLimitTestSupport
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizeRouter.authorizeRouter
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 認可エンドポイントのルーティングのテスト
 * Bukkitサーバーは使わず、Koinにモックのプラグインを登録し、DBとBukkitに触れるOAuthServiceをモックする
 */
class AuthorizeRouterTest {
    @TempDir
    lateinit var rootDirectory: File

    private val endpoint = "/oauth2/authorize"
    private val registeredRedirectUri = "https://app.example.com/callback"
    private val playerId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private val validParams = mapOf(
        "response_type" to "code",
        "client_id" to "client",
        "redirect_uri" to registeredRedirectUri,
        "scope" to "openid profile",
        "state" to "state-1",
        "code_challenge" to "challenge",
        "code_challenge_method" to "S256",
    )

    @BeforeEach
    fun setUp() {
        // 同梱ページはフロントエンドのビルドに依存するので、上書き用ページで描画する
        File(rootDirectory, AuthorizePageRenderer.OVERRIDE_PATH).apply {
            parentFile.mkdirs()
            writeText("<html>${AuthorizePageRenderer.PLACEHOLDER}</html>")
        }
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("AuthorizeRouterTest")
        val pluginDirectory = mockk<PluginDirectory>()
        every { pluginDirectory.getRootDirectory() } returns rootDirectory
        startKoin {
            modules(
                module {
                    single { plugin }
                    single { pluginDirectory }
                    single { JWTConfigData(issuer = "https://auth.example.com", keyId = UUID.randomUUID()) }
                    single { OAuthConfigData(applicationName = "MineAuth", logoUrl = "/assets/lock.svg") }
                    single { RateLimitTestSupport.disabled() }
                },
            )
        }

        val client = ClientData.PublicClientData("client", "Sample", listOf(registeredRedirectUri))
        mockkObject(OAuthService)
        every { OAuthService.getClientData(any()) } answers { client.takeIf { it.clientId == firstArg<String>() } }
        coEvery { OAuthService.authenticateUser(any(), any(), any()) } returns AuthenticationResult.Success(playerId)
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(OAuthService)
        stopKoin()
        OAuthRouter.authorizedData.clear()
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

    /** 有効なパラメータの一部を上書きしたParametersを作る（nullはキーごと削除する） */
    private fun params(vararg overrides: Pair<String, String?>): Parameters = Parameters.build {
        (validParams + overrides).forEach { (key, value) -> value?.let { append(key, it) } }
    }

    private suspend fun HttpClient.getAuthorize(vararg overrides: Pair<String, String?>): HttpResponse =
        get(endpoint) {
            url { parameters.appendAll(params(*overrides)) }
        }

    private suspend fun HttpClient.postAuthorize(vararg overrides: Pair<String, String?>): HttpResponse =
        submitForm(endpoint, params(*overrides))

    private fun HttpResponse.redirectParameters(): Parameters {
        assertEquals(HttpStatusCode.Found, status)
        return Url(assertNotNull(headers[HttpHeaders.Location])).parameters
    }

    private fun assertRejectedWithoutRedirect(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertNull(response.headers[HttpHeaders.Location])
    }

    private fun assertSecurityHeaders(response: HttpResponse) {
        assertEquals("frame-ancestors 'none'", response.headers["Content-Security-Policy"])
        assertEquals("DENY", response.headers["X-Frame-Options"])
        assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
    }

    @Nested
    @DisplayName("Open redirect protection")
    inner class OpenRedirectTest {

        @Test
        @DisplayName("POST without client_id returns 400 with no Location")
        fun postWithoutClientId() = withClient { client ->
            assertRejectedWithoutRedirect(
                client.postAuthorize("client_id" to null, "redirect_uri" to "https://evil.example"),
            )
        }

        @Test
        @DisplayName("Login submission with an unregistered redirect_uri does not authenticate")
        fun loginWithUnregisteredRedirectUri() = withClient { client ->
            val response = client.postAuthorize(
                "redirect_uri" to "https://evil.example",
                "username" to "Steve",
                "password" to "secret",
            )

            assertRejectedWithoutRedirect(response)
            coVerify(exactly = 0) { OAuthService.authenticateUser(any(), any(), any()) }
        }
    }

    @Nested
    @DisplayName("Authorization request")
    inner class AuthorizationRequestTest {

        @Test
        @DisplayName("POST without credentials renders the login page")
        fun postWithoutCredentialsRendersPage() = withClient { client ->
            val response = client.postAuthorize()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(ContentType.Text.Html, response.headers[HttpHeaders.ContentType]?.let(ContentType::parse)?.withoutParameters())
            assertTrue(response.bodyAsText().contains(""""clientId":"client""""))
        }

        @Test
        @DisplayName("Error redirect omits a state the request did not have")
        fun errorRedirectOmitsMissingState() = withClient { client ->
            val redirect = client.getAuthorize("state" to null).redirectParameters()

            assertEquals("invalid_request", redirect["error"])
            assertFalse("state" in redirect)
        }
    }

    @Nested
    @DisplayName("Login submission")
    inner class LoginSubmissionTest {

        @Test
        @DisplayName("Stores the granted scope resolved on the server")
        fun storesGrantedScope() = withClient { client ->
            val redirect = client.postAuthorize(
                "scope" to "openid foo offline_access",
                "username" to "Steve",
                "password" to "secret",
                "nonce" to "n-1",
            ).redirectParameters()

            assertEquals("state-1", redirect["state"])
            val stored = assertNotNull(OAuthRouter.authorizedData[assertNotNull(redirect["code"])])
            assertEquals("openid offline_access", stored.scope)
            assertEquals(playerId, stored.uniqueId)
            assertEquals("n-1", stored.nonce)
            // 発行される全トークンを束ねるグラントIDは、認可コードの発行時に決まる
            assertNotNull(stored.grantId)
        }
    }

    @Nested
    @DisplayName("Security headers")
    inner class SecurityHeadersTest {

        @Test
        @DisplayName("Set on the GET page")
        fun setOnGetPage() = withClient { client ->
            assertSecurityHeaders(client.getAuthorize())
        }

        @Test
        @DisplayName("Set when the body is not a form")
        fun setWhenBodyIsNotForm() = withClient { client ->
            val response = client.post(endpoint) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }

            assertTrue(response.status.value in 400..499, "status=${response.status}")
            assertNull(response.headers[HttpHeaders.Location])
            assertSecurityHeaders(response)
        }
    }
}
