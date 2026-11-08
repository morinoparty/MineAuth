package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import com.auth0.jwt.JWTCreator
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.GRANT_ID_CLAIM
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.RevokedGrantRepository
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * POST /oauth2/introspect のテスト
 * Bearer認証と同じ状態検証が適用され、対象外の種別がactiveにならないことを確認する（Issue #468）
 * サーバー管理者に許可されたクライアントだけが利用できることも確認する（Issue #480）
 */
class IntrospectRouterTest : OAuthRouteTestBase() {

    private val playerUniqueId = "00000000-0000-0000-0000-000000000001"

    @BeforeEach
    fun mockkBukkit() {
        // usernameの解決だけをモックする（Bukkitサーバーは初期化しない）
        val offlinePlayer = mockk<OfflinePlayer>()
        every { offlinePlayer.name } returns "Steve"
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(any<UUID>()) } returns offlinePlayer
    }

    @AfterEach
    fun unmockkBukkit() {
        unmockkStatic(Bukkit::class)
    }

    /**
     * 指定したクライアントのアクセストークン相当のクレームを設定する
     */
    private fun JWTCreator.Builder.withClientClaims(clientId: String, tokenId: String) = apply {
        withJWTId(tokenId)
        withAudience(clientId)
        withClaim("client_id", clientId)
        withClaim("playerUniqueId", playerUniqueId)
        withClaim("scope", "openid")
    }

    private suspend fun ApplicationTestBuilder.introspect(token: String, resourceServer: OAuthClientCreationResult): HttpResponse =
        client.submitForm(
            url = "/oauth2/introspect",
            formParameters = parameters {
                append("token", token)
                append("client_id", resourceServer.client.clientId)
                append("client_secret", resourceServer.clientSecret!!)
            }
        )

    /**
     * レスポンスが {"active": false} だけであることを確認する（RFC 7662 Section 2.2）
     */
    private fun assertInactive(body: JsonObject) {
        assertEquals(setOf("active"), body.keys)
        assertFalse(body.getValue("active").jsonPrimitive.boolean)
    }

    @Test
    @DisplayName("An ID token is inactive")
    fun idTokenIsInactive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val idToken = JwtTestSupport.sign(TokenKind.ID) {
            withSubject(playerUniqueId)
            withAudience(resourceServer.client.clientId)
        }

        assertInactive(introspect(idToken, resourceServer).jsonBody())
    }

    @Test
    @DisplayName("A token of a revoked grant becomes inactive")
    fun tokenOfRevokedGrantIsInactive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val grantId = UUID.randomUUID().toString()
        val token = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClientClaims(resourceServer.client.clientId, UUID.randomUUID().toString())
            withClaim(GRANT_ID_CLAIM, grantId)
        }
        assertTrue(introspect(token, resourceServer).jsonBody().getValue("active").jsonPrimitive.boolean)

        RevokedGrantRepository.revoke(grantId, resourceServer.client.clientId)
            .getOrElse { error("Failed to revoke the grant: $it") }

        assertInactive(introspect(token, resourceServer).jsonBody())
    }

    @Test
    @DisplayName("A client without the permission gets 403 and learns nothing about the token")
    fun clientWithoutPermissionIsRejected() = oauthTestApplication {
        val caller = registerClient(ClientType.CONFIDENTIAL)
        val validToken = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClientClaims(caller.client.clientId, UUID.randomUUID().toString())
        }

        val response = introspect(validToken, caller)
        val responseText = response.bodyAsText()

        assertEquals(HttpStatusCode.Forbidden, response.status)
        // 認証は成功しているので、再認証を促すチャレンジは付けない
        assertNull(response.headers[HttpHeaders.WWWAuthenticate])
        val body = response.jsonBody()
        assertEquals(setOf("error", "error_description"), body.keys)
        assertEquals("unauthorized_client", body.getValue("error").jsonPrimitive.content)
        assertTrue(body.getValue("error_description").jsonPrimitive.content.contains("administrator"))

        // 有効なトークンと不正なトークンで応答が変わらなければ、トークンの状態は伝わっていない
        val invalidTokenResponse = introspect("not-a-token", caller)
        assertEquals(HttpStatusCode.Forbidden, invalidTokenResponse.status)
        assertEquals(responseText, invalidTokenResponse.bodyAsText())
    }
}
