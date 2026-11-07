package party.morino.mineauth.core.web

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationStrategy
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.file.data.JWTConfigData
import java.util.UUID

/**
 * BearerChallengeのテスト
 * RFC 6750 Section 3 のヘッダー文法と、Ktorのjwtプロバイダとの連携を確認する
 */
class BearerChallengeTest {

    @Nested
    @DisplayName("headerValue")
    inner class HeaderValue {

        @Test
        @DisplayName("Challenge without an error carries only the realm")
        fun challengeWithoutErrorCarriesOnlyRealm() {
            assertEquals("Bearer realm=\"MineAuth\"", BearerChallenge.headerValue("MineAuth"))
        }

        @Test
        @DisplayName("Invalid token challenge carries error and description")
        fun invalidTokenChallengeCarriesErrorAndDescription() {
            val header = BearerChallenge.headerValue(
                realm = "MineAuth",
                error = BearerChallenge.ErrorCode.INVALID_TOKEN,
                errorDescription = "Invalid token",
            )

            assertEquals(
                "Bearer realm=\"MineAuth\", error=\"invalid_token\", error_description=\"Invalid token\"",
                header,
            )
        }

        @Test
        @DisplayName("Insufficient scope challenge carries the scope")
        fun insufficientScopeChallengeCarriesScope() {
            val header = BearerChallenge.headerValue(
                realm = "MineAuth",
                error = BearerChallenge.ErrorCode.INSUFFICIENT_SCOPE,
                scope = "plugin",
            )

            assertEquals("Bearer realm=\"MineAuth\", error=\"insufficient_scope\", scope=\"plugin\"", header)
        }

        @Test
        @DisplayName("Realm is escaped as a quoted-string and loses control characters")
        fun realmIsEscapedAsQuotedString() {
            // CR/LFが残るとヘッダーインジェクションになるため除去されることも確認する
            val header = BearerChallenge.headerValue("a\"b\\c\r\nd")

            assertEquals("Bearer realm=\"a\\\"b\\\\cd\"", header)
        }

        @Test
        @DisplayName("Description keeps only the characters RFC 6750 allows")
        fun descriptionKeepsOnlyAllowedCharacters() {
            val header = BearerChallenge.headerValue(
                realm = "MineAuth",
                error = BearerChallenge.ErrorCode.INVALID_TOKEN,
                errorDescription = "bad \"token\"\\\r\n期限切れ!",
            )

            assertTrue(header.endsWith("error_description=\"bad token!\""), header)
        }

        @Test
        @DisplayName("Description with no allowed characters is omitted")
        fun descriptionWithNoAllowedCharactersIsOmitted() {
            val header = BearerChallenge.headerValue(
                realm = "MineAuth",
                error = BearerChallenge.ErrorCode.INVALID_TOKEN,
                errorDescription = "期限切れ",
            )

            assertFalse(header.contains("error_description"), header)
        }
    }

    @Nested
    @DisplayName("isCredentialMissing")
    inner class IsCredentialMissing {

        @Test
        @DisplayName("Only NoCredentials failures count as missing credentials")
        fun onlyNoCredentialsFailuresCountAsMissing() {
            val noCredentials = AuthenticationFailedCause.NoCredentials
            val invalid = AuthenticationFailedCause.InvalidCredentials

            assertTrue(BearerChallenge.isCredentialMissing(listOf(noCredentials), null))
            assertFalse(BearerChallenge.isCredentialMissing(listOf(invalid), "Bearer token"))
            assertFalse(BearerChallenge.isCredentialMissing(listOf(noCredentials, invalid), "bearer token"))
            assertFalse(BearerChallenge.isCredentialMissing(emptyList(), null))
        }

        @Test
        @DisplayName("Another authentication scheme counts as missing credentials")
        fun otherSchemeCountsAsMissing() {
            val failures = listOf(AuthenticationFailedCause.InvalidCredentials)

            assertTrue(BearerChallenge.isCredentialMissing(failures, "Basic dXNlcjpwYXNz"))
        }
    }

    /**
     * WebServerのプラグインディスパッチャと同じ「2プロバイダ + Optional」構成での挙動を確認する
     * Application.module()はOpenTelemetryを初期化するため、認証部分のみを同じ設定で組み立てる
     */
    @Nested
    @DisplayName("respondProviderFailure on an optional route")
    inner class RespondProviderFailure {

        @BeforeEach
        fun setUp() {
            startKoin {
                modules(module { single { JWTConfigData(realm = "MineAuth", keyId = UUID.randomUUID()) } })
            }
        }

        @AfterEach
        fun tearDown() {
            stopKoin()
        }

        private fun ApplicationTestBuilder.installOptionalRoute() {
            application {
                install(ContentNegotiation) { json(Json) }
                install(Authentication) {
                    listOf(JwtCompleteCode.USER_TOKEN, JwtCompleteCode.SERVICE_TOKEN).forEach { provider ->
                        jwt(provider.code) {
                            verifier(JWT.require(Algorithm.HMAC256("bearer-challenge-test")).build())
                            validate { null }
                            challenge { _, _ -> BearerChallenge.respondProviderFailure(call, "Token is not valid") }
                        }
                    }
                }
                routing {
                    authenticate(
                        JwtCompleteCode.USER_TOKEN.code,
                        JwtCompleteCode.SERVICE_TOKEN.code,
                        strategy = AuthenticationStrategy.Optional,
                    ) {
                        get("/optional") { call.respondText("reached") }
                    }
                }
            }
        }

        @Test
        @DisplayName("Request without a token reaches the handler")
        fun requestWithoutTokenReachesHandler() = testApplication {
            installOptionalRoute()

            val response = client.get("/optional")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("reached", response.bodyAsText())
        }

        @Test
        @DisplayName("Invalid token is rejected with an invalid_token challenge")
        fun invalidTokenIsRejected() = testApplication {
            installOptionalRoute()

            val response = client.get("/optional") { header(HttpHeaders.Authorization, "Bearer not-a-jwt") }

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals(
                "Bearer realm=\"MineAuth\", error=\"invalid_token\", error_description=\"Token is not valid\"",
                response.headers[HttpHeaders.WWWAuthenticate],
            )
            assertTrue(response.bodyAsText().contains("\"code\":\"invalid_token\""))
        }
    }
}
