package party.morino.mineauth.core.web.router.auth.oauth

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
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.web.ratelimit.RateLimitTestSupport
import party.morino.mineauth.core.web.router.auth.oauth.EndSessionRouter.endSessionRouter
import party.morino.mineauth.core.web.router.auth.oauth.IntrospectRouter.introspectRouter
import party.morino.mineauth.core.web.router.auth.oauth.RevokeRouter.revokeRouter
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.tokenRouter
import java.nio.file.Path
import java.util.Base64
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
    @DisplayName("Empty grant_type is treated as missing")
    fun emptyGrantType() = routeTest {
        assertInvalidRequest(postForm("/token", "grant_type=&client_id=client"), "grant_type")
    }
}
