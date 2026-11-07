package party.morino.mineauth.core.web

import arrow.core.getOrElse
import com.auth0.jwt.JWTCreator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.plugin.pluginModule
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouteTestBase
import party.morino.mineauth.core.web.router.auth.oauth.TokenStateValidator
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Application.module()のBearer認証プロバイダのテスト
 * 実際のvalidateブロックを通して、種別の絞り込みと状態の検証が適用されることを確認する
 *
 * テレメトリを無効にした設定を使うため、OpenTelemetry SDKとMockBukkitは初期化しない
 */
class WebServerBearerAuthTest : OAuthRouteTestBase() {

    // アクセストークンのプロバイダのみで保護されたルート
    private val userTokenRoute = "/hello"

    // アクセストークンとサービストークンの両方のプロバイダを通るルート（プラグインの有無によらず認証は実行される）
    private val pluginRoute = "/api/v1/plugins/unknown/resource"

    @BeforeEach
    fun setUpApplicationDependencies() {
        loadKoinModules(pluginModule)
        // /helloのレスポンスに使うプレイヤー名の解決だけをモックする
        val offlinePlayer = mockk<OfflinePlayer>()
        every { offlinePlayer.name } returns "Steve"
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(any<UUID>()) } returns offlinePlayer
    }

    @AfterEach
    fun unmockBukkit() {
        unmockkStatic(Bukkit::class)
    }

    private fun bearerApplication(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application { module() }
        block()
    }

    private suspend fun ApplicationTestBuilder.getWithBearer(path: String, token: String) =
        client.get(path) { header(HttpHeaders.Authorization, "Bearer $token") }

    private fun JWTCreator.Builder.withUserClaims(clientId: String, tokenId: String) = apply {
        withJWTId(tokenId)
        withAudience(clientId)
        withClaim("client_id", clientId)
        withClaim("playerUniqueId", "00000000-0000-0000-0000-000000000001")
        withClaim("scope", "openid")
    }

    /**
     * サービストークンを発行してDBへ登録する
     *
     * @return サービストークン、そのjti、アカウントID
     */
    private suspend fun issueServiceToken(): Triple<String, String, String> {
        val account = AccountRepository.create(AccountType.SERVICE, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        val tokenId = UUID.randomUUID().toString()
        ServiceAccountTokenRepository.create(tokenId, account.accountId, "hash", "console")
            .getOrElse { error("Failed to create token: $it") }
        val token = JwtTestSupport.sign(TokenKind.SERVICE) {
            withJWTId(tokenId)
            withClaim("account_id", account.accountId)
        }
        return Triple(token, tokenId, account.accountId)
    }

    private suspend fun lastUsedAtOf(tokenId: String): LocalDateTime? =
        ServiceAccountTokenRepository.findByTokenId(tokenId).getOrElse { error("Not found: $it") }.lastUsedAt

    @Test
    @DisplayName("New and legacy access tokens authenticate")
    fun accessTokensAuthenticate() = bearerApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val typed = JwtTestSupport.sign(TokenKind.ACCESS) { withUserClaims(clientId, UUID.randomUUID().toString()) }
        val legacy = JwtTestSupport.signLegacy("token") { withUserClaims(clientId, UUID.randomUUID().toString()) }

        assertEquals(HttpStatusCode.OK, getWithBearer(userTokenRoute, typed).status)
        assertEquals(HttpStatusCode.OK, getWithBearer(userTokenRoute, legacy).status)
    }

    @Test
    @DisplayName("Refresh, ID and service tokens do not authenticate as access tokens")
    fun otherKindsAreRejected() = bearerApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val refresh = JwtTestSupport.sign(TokenKind.REFRESH) { withUserClaims(clientId, UUID.randomUUID().toString()) }
        val idToken = JwtTestSupport.sign(TokenKind.ID) {
            withSubject("00000000-0000-0000-0000-000000000001")
            withAudience(clientId)
        }
        val (service, _, _) = issueServiceToken()

        listOf(refresh, idToken, service).forEach { token ->
            assertEquals(HttpStatusCode.Unauthorized, getWithBearer(userTokenRoute, token).status)
        }
    }

    @Test
    @DisplayName("Revoked access tokens and tokens of deleted clients do not authenticate")
    fun revokedAndOrphanedTokensAreRejected() = bearerApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val revokedId = UUID.randomUUID().toString()
        val revoked = JwtTestSupport.sign(TokenKind.ACCESS) { withUserClaims(clientId, revokedId) }
        RevokedTokenRepository.revoke(revokedId, TokenType.ACCESS_TOKEN, clientId, LocalDateTime.now().plusMinutes(5))
            .getOrElse { error("Failed to revoke: $it") }

        val deletedClientId = registerClient(ClientType.PUBLIC).client.clientId
        val orphaned = JwtTestSupport.sign(TokenKind.ACCESS) {
            withUserClaims(deletedClientId, UUID.randomUUID().toString())
        }
        OAuthClientRepository.delete(deletedClientId).getOrElse { error("Failed to delete: $it") }

        assertEquals(HttpStatusCode.Unauthorized, getWithBearer(userTokenRoute, revoked).status)
        assertEquals(HttpStatusCode.Unauthorized, getWithBearer(userTokenRoute, orphaned).status)
    }

    @Test
    @DisplayName("Provider challenges send WWW-Authenticate on the real module")
    fun providerChallengesSendWwwAuthenticate() = bearerApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val refresh = JwtTestSupport.sign(TokenKind.REFRESH) { withUserClaims(clientId, UUID.randomUUID().toString()) }
        val revokedId = UUID.randomUUID().toString()
        val revoked = JwtTestSupport.sign(TokenKind.ACCESS) { withUserClaims(clientId, revokedId) }
        RevokedTokenRepository.revoke(revokedId, TokenType.ACCESS_TOKEN, clientId, LocalDateTime.now().plusMinutes(5))
            .getOrElse { error("Failed to revoke: $it") }

        // トークンなし: error属性を付けない（RFC 6750 Section 3.1）
        val missing = client.get(userTokenRoute)
        assertEquals(HttpStatusCode.Unauthorized, missing.status)
        assertEquals("Bearer realm=\"morino.party\"", missing.headers[HttpHeaders.WWWAuthenticate])

        // Verifierが拒否する種別違いと、validateブロックが拒否する失効済みは同じinvalid_tokenになる
        listOf(refresh, revoked).forEach { token ->
            val response = getWithBearer(userTokenRoute, token)
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertContains(assertNotNull(response.headers[HttpHeaders.WWWAuthenticate]), "error=\"invalid_token\"")
        }
    }

    /**
     * TokenStateValidatorを指定の例外を投げるものへ差し替えて、記録したログを渡しながらアプリケーションを実行する
     *
     * @param failure TokenStateValidatorが投げる例外
     * @param block テスト本体
     */
    private fun withFailingValidator(
        failure: Exception,
        block: suspend ApplicationTestBuilder.(MutableList<LogRecord>) -> Unit
    ) {
        val validator = mockk<TokenStateValidator>()
        every { validator.validateBlocking(any(), any()) } throws failure
        loadKoinModules(module { single { validator } })

        // ロガーはOAuthRouteTestBaseがモックのプラグインへ登録したもの
        val records = CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) {
                records.add(record)
            }

            override fun flush() = Unit

            override fun close() = Unit
        }
        val logger = Logger.getLogger("OAuthRouteTest")
        logger.addHandler(handler)
        try {
            bearerApplication { block(records) }
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    @DisplayName("A failing validator answers the invalid_token challenge for both providers")
    fun failingValidatorAnswersChallenge() {
        val userToken = JwtTestSupport.sign(TokenKind.ACCESS) { withUserClaims("client", UUID.randomUUID().toString()) }
        val serviceToken = JwtTestSupport.sign(TokenKind.SERVICE) {
            withJWTId(UUID.randomUUID().toString())
            withClaim("account_id", "account")
        }

        withFailingValidator(IllegalStateException("database is down")) { records ->
            listOf(userTokenRoute to userToken, pluginRoute to serviceToken).forEach { (route, token) ->
                val response = getWithBearer(route, token)

                // 素の401ではなく、通常のchallengeと同じヘッダーと本文を返す
                assertEquals(HttpStatusCode.Unauthorized, response.status, route)
                assertContains(assertNotNull(response.headers[HttpHeaders.WWWAuthenticate]), "error=\"invalid_token\"")
                assertContains(response.bodyAsText(), "\"code\":\"invalid_token\"")

                // 失敗は管理者に伝わるが、トークンはログに残らない
                assertTrue(records.any { it.level == Level.SEVERE }, route)
                assertTrue(records.none { it.message.contains(token) }, route)
                records.clear()
            }
        }
    }

    @Test
    @DisplayName("A failing validator records the failure on the span")
    fun failingValidatorRecordsSpanAttribute() = runBlocking {
        val span = mockk<Span>(relaxed = true)

        val result = BearerChallenge.failClosed<JWTPrincipal>(span, Logger.getLogger("OAuthRouteTest")) {
            throw IllegalStateException("database is down")
        }

        assertNull(result)
        verify { span.setAttribute(TelemetryAttributes.AUTH_RESULT, "validation_error") }
    }

    @Test
    @DisplayName("Cancellation is not treated as a validation failure")
    fun cancellationIsRethrown() {
        val span = mockk<Span>(relaxed = true)

        assertFailsWith<CancellationException> {
            runBlocking {
                BearerChallenge.failClosed<JWTPrincipal>(span, Logger.getLogger("OAuthRouteTest")) {
                    throw CancellationException("cancelled")
                }
            }
        }
        verify(exactly = 0) { span.setAttribute(TelemetryAttributes.AUTH_RESULT, any<String>()) }
    }

    @Test
    @DisplayName("A valid service token authenticates and records its last use")
    fun serviceTokenRecordsLastUse() = bearerApplication {
        val (token, tokenId, _) = issueServiceToken()

        getWithBearer(pluginRoute, token)

        assertNotNull(lastUsedAtOf(tokenId))
    }

    @Test
    @DisplayName("A reissued service token does not authenticate and records no use")
    fun reissuedServiceTokenIsRejected() = bearerApplication {
        val (token, tokenId, accountId) = issueServiceToken()
        ServiceAccountTokenRepository.revokeAndCreate(accountId, UUID.randomUUID().toString(), "hash2", "console")
            .getOrElse { error("Failed to reissue: $it") }

        getWithBearer(pluginRoute, token)

        assertNull(lastUsedAtOf(tokenId))
    }
}
