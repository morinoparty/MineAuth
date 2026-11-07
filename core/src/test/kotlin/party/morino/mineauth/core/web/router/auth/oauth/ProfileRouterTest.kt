package party.morino.mineauth.core.web.router.auth.oauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.web.BearerChallenge
import party.morino.mineauth.core.web.JwtCompleteCode
import party.morino.mineauth.core.web.router.auth.oauth.ProfileRouter.profileRouter
import java.time.Instant
import java.util.UUID

/**
 * UserInfoエンドポイントのテスト
 *
 * 実際のprofileRouter()を、WebServerと同じchallengeを持つjwtプロバイダの配下で動かす。
 * Application.module()はOpenTelemetryを初期化するため、認証部分のみを同じ設定で組み立てる。
 */
class ProfileRouterTest {

    private val playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000")
    private val algorithm = Algorithm.HMAC256("profile-router-test")

    @BeforeEach
    fun setUp() {
        startKoin {
            modules(
                module {
                    single { JWTConfigData(realm = "MineAuth", keyId = UUID.randomUUID()) }
                    single { MineAuthConfig() }
                },
            )
        }
        // プレイヤー名の解決だけをBukkitのstatic APIから差し替える
        val player = mockk<OfflinePlayer>()
        every { player.name } returns "Steve"
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(playerId) } returns player
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Bukkit::class)
        stopKoin()
    }

    /** 指定時刻に期限切れとなるアクセストークンを発行する */
    private fun tokenExpiringAt(expiresAt: Instant, scope: String = "openid profile"): String = JWT.create()
        .withClaim("playerUniqueId", playerId.toString())
        .withClaim("scope", scope)
        .withExpiresAt(expiresAt)
        .sign(algorithm)

    private fun validToken(): String = tokenExpiringAt(Instant.now().plusSeconds(3600))

    private fun ApplicationTestBuilder.installUserInfo() {
        application {
            install(ContentNegotiation) { json(Json) }
            install(Authentication) {
                jwt(JwtCompleteCode.USER_TOKEN.code) {
                    verifier(JWT.require(algorithm).build())
                    validate { JWTPrincipal(it.payload) }
                    challenge { _, _ ->
                        BearerChallenge.respondProviderFailure(call, "Token is not valid or has expired")
                    }
                }
            }
            routing {
                route("/oauth2") { profileRouter() }
            }
        }
    }

    @Test
    @DisplayName("POST with a Bearer header returns the same claims as GET")
    fun postReturnsSameClaimsAsGet() = testApplication {
        installUserInfo()
        val token = validToken()

        val getResponse = client.get("/oauth2/userinfo") { header(HttpHeaders.Authorization, "Bearer $token") }
        val postResponse = client.post("/oauth2/userinfo") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        assertEquals(HttpStatusCode.OK, postResponse.status)
        assertTrue(postResponse.bodyAsText().contains("\"sub\":\"$playerId\""))
        assertEquals(getResponse.bodyAsText(), postResponse.bodyAsText())
    }

    @Test
    @DisplayName("The email claim follows emailFormat after reload")
    fun emailFollowsReload() = testApplication {
        installUserInfo()
        val token = tokenExpiringAt(Instant.now().plusSeconds(3600), scope = "openid email")

        val before = client.get("/oauth2/userinfo") { header(HttpHeaders.Authorization, "Bearer $token") }
        assertFalse(before.bodyAsText().contains("\"email\":"))

        // ConfigLoaderと同じく、設定の登録を置き換える
        loadKoinModules(module { single { MineAuthConfig(server = ServerConfig(emailFormat = "<uuid>@example.com")) } })
        val after = client.get("/oauth2/userinfo") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertTrue(after.bodyAsText().contains("\"email\":\"$playerId@example.com\""))
    }

    @Test
    @DisplayName("Missing token gets a challenge without an error attribute")
    fun missingTokenGetsChallengeWithoutError() = testApplication {
        installUserInfo()

        val response = client.get("/oauth2/userinfo")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(response.bodyAsText().contains("\"code\":\"authentication_required\""))
    }

    @Test
    @DisplayName("Basic credentials get a challenge without an error attribute")
    fun basicCredentialsGetChallengeWithoutError() = testApplication {
        installUserInfo()

        val response = client.get("/oauth2/userinfo") { header(HttpHeaders.Authorization, "Basic dXNlcjpwYXNz") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(response.bodyAsText().contains("\"code\":\"authentication_required\""))
    }

    @Test
    @DisplayName("Expired token gets an invalid_token challenge")
    fun expiredTokenGetsInvalidTokenChallenge() = testApplication {
        installUserInfo()
        val token = tokenExpiringAt(Instant.now().minusSeconds(3600))

        val response = client.post("/oauth2/userinfo") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            "Bearer realm=\"MineAuth\", error=\"invalid_token\", " +
                "error_description=\"Token is not valid or has expired\"",
            response.headers[HttpHeaders.WWWAuthenticate],
        )
        assertTrue(response.bodyAsText().contains("\"code\":\"invalid_token\""))
    }

    @Test
    @DisplayName("POST does not accept the token as a form parameter")
    fun postDoesNotAcceptFormParameter() = testApplication {
        installUserInfo()

        val response = client.post("/oauth2/userinfo") {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("access_token=${validToken()}")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
    }
}
