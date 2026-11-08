package party.morino.mineauth.core.web

import arrow.core.getOrElse
import com.auth0.jwt.JWTCreator
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
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
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouteTestBase
import party.morino.mineauth.core.web.router.auth.oauth.TokenStateValidator
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
