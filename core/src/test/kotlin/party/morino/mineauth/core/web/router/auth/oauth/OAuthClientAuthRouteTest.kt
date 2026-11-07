package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.web.ratelimit.RateLimitTestSupport
import party.morino.mineauth.core.web.router.auth.oauth.EndSessionRouter.endSessionRouter
import party.morino.mineauth.core.web.router.auth.oauth.IntrospectRouter.introspectRouter
import party.morino.mineauth.core.web.router.auth.oauth.RevokeRouter.revokeRouter
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.tokenRouter
import java.nio.file.Path
import java.util.Base64
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * トークン系エンドポイントのクライアント認証エラーとパラメータ検証のルートテスト
 *
 * クライアントはテストごとのSQLiteに登録する。トークン自体の検証はこのテストの対象外なので、
 * JwtProviderのVerifierは常に拒否するモックに差し替える
 */
class OAuthClientAuthRouteTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("OAuthClientAuthRouteTest")
        startKoin {
            modules(
                module {
                    single { plugin }
                    single { RateLimitTestSupport.disabled() }
                    single { Argon2Gate() }
                }
            )
        }

        Database.connect(url = "jdbc:sqlite:${tempDir.resolve("test.db")}", driver = "org.sqlite.JDBC")
        DatabaseMigrator.migrate()

        val rejectingVerifier = mockk<JWTVerifier>()
        every { rejectingVerifier.verify(any<String>()) } throws JWTVerificationException("rejected")
        mockkObject(JwtProvider)
        every { JwtProvider.refreshTokenVerifier } returns rejectingVerifier
        every { JwtProvider.introspectionVerifier } returns rejectingVerifier
        every { JwtProvider.revocationVerifier } returns rejectingVerifier
        every { JwtProvider.idTokenHintVerifier } returns rejectingVerifier
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(JwtProvider)
        stopKoin()
    }

    /** "username:password" をBasicヘッダーの値にエンコードする */
    private fun basic(userPass: String): String =
        "Basic " + Base64.getEncoder().encodeToString(userPass.toByteArray())

    /** 指定した種別のクライアントをDBに登録する */
    private suspend fun createClient(
        clientType: ClientType,
        introspectionAllowed: Boolean = false
    ): OAuthClientCreationResult {
        val account = AccountRepository.create(AccountType.PLAYER, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        return OAuthClientRepository.create(
            "Test Client",
            clientType,
            listOf("https://example.com/callback"),
            account.accountId,
            introspectionAllowed
        ).getOrElse { error("Failed to create client: $it") }
    }

    /** 対象ルートを登録してテストブロックを実行する */
    private fun routeTest(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                tokenRouter()
                revokeRouter()
                introspectRouter()
                endSessionRouter()
            }
        }
        block()
    }

    /** フォーム形式のPOSTを送る（重複パラメータをそのまま送るため本文は文字列で組み立てる） */
    private suspend fun ApplicationTestBuilder.postForm(
        path: String,
        body: String,
        authorization: String? = null
    ): HttpResponse = client.post(path) {
        header(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded.toString())
        authorization?.let { header(HttpHeaders.Authorization, it) }
        setBody(body)
    }

    /** レスポンス本文をOAuthエラーレスポンスとして読み取る */
    private suspend fun HttpResponse.oauthError(): OAuthErrorResponse =
        Json.decodeFromString(OAuthErrorResponse.serializer(), bodyAsText())

    /** 401 invalid_clientとBasicチャレンジを検証する */
    private suspend fun assertInvalidClientChallenge(response: HttpResponse) {
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("invalid_client", response.oauthError().error)
        assertEquals(
            "Basic realm=\"MineAuth\", charset=\"UTF-8\"",
            response.headers[HttpHeaders.WWWAuthenticate]
        )
    }

    /** 400 invalid_requestを検証し、チャレンジが付かないことも確認する */
    private suspend fun assertInvalidRequest(response: HttpResponse, descriptionPart: String) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val error = response.oauthError()
        assertEquals("invalid_request", error.error)
        assertTrue(error.errorDescription.orEmpty().contains(descriptionPart), error.errorDescription)
        assertNull(response.headers[HttpHeaders.WWWAuthenticate])
    }

    @Test
    @DisplayName("Revoke with an empty Basic secret returns 401 with realm")
    fun revokeWithEmptyBasicSecret() = routeTest {
        assertInvalidClientChallenge(postForm("/revoke", "token=abc", basic("client:")))
    }

    @Test
    @DisplayName("Introspect with an empty Basic secret returns 401 with realm")
    fun introspectWithEmptyBasicSecret() = routeTest {
        assertInvalidClientChallenge(postForm("/introspect", "token=abc", basic("client:")))
    }

    @Test
    @DisplayName("Revoke with a body client_id and no secret returns 401")
    fun revokeWithoutSecret() = routeTest {
        assertInvalidClientChallenge(postForm("/revoke", "token=abc&client_id=client"))
    }

    @Test
    @DisplayName("Revoke and introspect without client authentication return 401 with realm")
    fun noClientAuthentication() = routeTest {
        listOf("/revoke", "/introspect").forEach { path ->
            assertInvalidClientChallenge(postForm(path, "token=abc"))
            // 空のclient_idとBasic以外のスキームも、クライアント認証がないものとして扱う
            assertInvalidClientChallenge(postForm(path, "token=abc&client_id="))
            assertInvalidClientChallenge(postForm(path, "token=abc", "Bearer some-token"))
        }
    }

    @Test
    @DisplayName("Token endpoint without client_id stays invalid_request")
    fun tokenEndpointWithoutClientId() = routeTest {
        assertInvalidRequest(postForm("/token", "grant_type=refresh_token&refresh_token=abc"), "client_id")
    }

    @Test
    @DisplayName("Revoke and introspect keep rejecting a repeated client_id as invalid_request")
    fun repeatedClientIdAtClientAuthEndpoints() = routeTest {
        listOf("/revoke", "/introspect").forEach { path ->
            assertInvalidRequest(postForm(path, "token=abc&client_id=a&client_id=b"), "repeated: client_id")
        }
    }

    @Test
    @DisplayName("Rejected end_session does not write the query string to the log")
    fun rejectedEndSessionLogsPathOnly() {
        // このテストのプラグインのロガーは親へ伝わるので、ルートロガーで捕捉する
        val logged = RecordingLogHandler()
        val rootLogger = Logger.getLogger("")
        rootLogger.addHandler(logged)
        try {
            routeTest {
                val response = client.get("/end_session?id_token_hint=signed-id-token&client_id=client")
                assertEquals(HttpStatusCode.BadRequest, response.status)
            }
        } finally {
            rootLogger.removeHandler(logged)
        }

        // 行が出力されていることも確認し、ログが捕捉できないときに誤って成功しないようにする
        assertContains(logged.warnings, "OAuth error at /end_session: invalid_request - Invalid id_token_hint")
        assertTrue(logged.warnings.none { it.contains("signed-id-token") }, logged.warnings.toString())
    }

    @Test
    @DisplayName("Malformed Basic header returns 401 with realm")
    fun malformedBasicHeader() = routeTest {
        assertInvalidClientChallenge(postForm("/revoke", "token=abc", "Basic !!!not-base64!!!"))
        assertInvalidClientChallenge(postForm("/token", "grant_type=refresh_token", "Basic !!!not-base64!!!"))
    }

    @Test
    @DisplayName("Token rejects a confidential client with an empty Basic secret")
    fun tokenRejectsEmptyBasicSecret() = routeTest {
        val client = createClient(ClientType.CONFIDENTIAL).client
        val response = postForm("/token", "grant_type=refresh_token&refresh_token=abc", basic("${client.clientId}:"))
        assertInvalidClientChallenge(response)
    }

    @Test
    @DisplayName("Wrong secret returns 401 with realm")
    fun wrongSecret() = routeTest {
        val client = createClient(ClientType.CONFIDENTIAL).client
        assertInvalidClientChallenge(postForm("/introspect", "token=abc", basic("${client.clientId}:wrong")))
    }

    @Test
    @DisplayName("Accepts Basic with a matching body client_id")
    fun acceptsBasicWithMatchingClientId() = routeTest {
        val created = createClient(ClientType.CONFIDENTIAL, introspectionAllowed = true)
        val clientId = created.client.clientId
        val response = postForm(
            "/introspect",
            "token=abc&client_id=$clientId",
            basic("$clientId:${created.clientSecret}")
        )
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    @DisplayName("Accepts client_secret_post")
    fun acceptsClientSecretPost() = routeTest {
        val created = createClient(ClientType.CONFIDENTIAL, introspectionAllowed = true)
        val body = "token=abc&client_id=${created.client.clientId}&client_secret=${created.clientSecret}"
        assertEquals(HttpStatusCode.OK, postForm("/introspect", body).status)
    }

    @Test
    @DisplayName("Rejects Basic with a differing body client_id")
    fun rejectsBasicWithDifferingClientId() = routeTest {
        val created = createClient(ClientType.CONFIDENTIAL)
        val response = postForm(
            "/introspect",
            "token=abc&client_id=other",
            basic("${created.client.clientId}:${created.clientSecret}")
        )
        assertInvalidRequest(response, "client_id")
    }

    @Test
    @DisplayName("Rejects Basic with a body client_secret")
    fun rejectsBasicWithBodySecret() = routeTest {
        val created = createClient(ClientType.CONFIDENTIAL)
        val response = postForm(
            "/introspect",
            "token=abc&client_secret=${created.clientSecret}",
            basic("${created.client.clientId}:${created.clientSecret}")
        )
        assertInvalidRequest(response, "Multiple client authentication methods")
    }

    @Test
    @DisplayName("Empty grant_type is treated as missing")
    fun emptyGrantType() = routeTest {
        assertInvalidRequest(postForm("/token", "grant_type=&client_id=client"), "grant_type")
    }

    @Test
    @DisplayName("Empty code is treated as missing")
    fun emptyCode() = routeTest {
        val body = "grant_type=authorization_code&client_id=client&code=&redirect_uri=https%3A%2F%2Fa.example&code_verifier=v"
        assertInvalidRequest(postForm("/token", body), "code")
    }

    @Test
    @DisplayName("Repeated code is rejected")
    fun repeatedCode() = routeTest {
        val body = "grant_type=authorization_code&client_id=client&code=a&code=b&redirect_uri=https%3A%2F%2Fa.example&code_verifier=v"
        assertInvalidRequest(postForm("/token", body), "repeated: code")
    }

    @Test
    @DisplayName("Public client with client_id only reaches the refresh_token check")
    fun publicClientReachesRefreshTokenCheck() = routeTest {
        val clientId = createClient(ClientType.PUBLIC).client.clientId
        // クライアント認証を通過した場合にのみ refresh_token の検証へ到達する
        assertInvalidRequest(
            postForm("/token", "grant_type=refresh_token&client_id=$clientId&refresh_token="),
            "refresh_token"
        )
        assertInvalidRequest(
            postForm("/token", "grant_type=refresh_token&client_id=$clientId&refresh_token=a&refresh_token=b"),
            "repeated: refresh_token"
        )
    }

    @Test
    @DisplayName("Revoke rejects empty and repeated token")
    fun revokeRejectsEmptyAndRepeatedToken() = routeTest {
        assertInvalidRequest(postForm("/revoke", "token="), "token")
        assertInvalidRequest(postForm("/revoke", "token=a&token=b"), "repeated: token")
    }

    @Test
    @DisplayName("Introspect rejects repeated token_type_hint")
    fun introspectRejectsRepeatedHint() = routeTest {
        val body = "token=abc&token_type_hint=access_token&token_type_hint=refresh_token"
        assertInvalidRequest(postForm("/introspect", body), "repeated: token_type_hint")
    }
}
