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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.repository.RevokedGrantRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository
import party.morino.mineauth.core.repository.TokenType
import java.sql.SQLException
import java.time.Duration
import java.time.LocalDateTime
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
    fun mockBukkit() {
        // usernameの解決だけをモックする（MockBukkitは初期化しない）
        val offlinePlayer = mockk<OfflinePlayer>()
        every { offlinePlayer.name } returns "Steve"
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(any<UUID>()) } returns offlinePlayer
    }

    @AfterEach
    fun unmockBukkit() {
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
        introspect(token, resourceServer.client.clientId, resourceServer.clientSecret!!)

    private suspend fun ApplicationTestBuilder.introspect(token: String, clientId: String, clientSecret: String): HttpResponse =
        client.submitForm(
            url = "/oauth2/introspect",
            formParameters = parameters {
                append("token", token)
                append("client_id", clientId)
                append("client_secret", clientSecret)
            }
        )

    /**
     * レスポンスが {"active": false} だけであることを確認する（RFC 7662 Section 2.2）
     */
    private fun assertInactive(body: JsonObject) {
        assertEquals(setOf("active"), body.keys)
        assertFalse(body.getValue("active").jsonPrimitive.boolean)
    }

    /**
     * 有効なサービスアカウントのトークンを発行してDBへ登録する
     *
     * @return サービストークンとそのjti、アカウントID
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

    @Test
    @DisplayName("An access token is active with token_type Bearer and metadata")
    fun accessTokenIsActive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val clientId = resourceServer.client.clientId
        val token = JwtTestSupport.sign(TokenKind.ACCESS) { withClientClaims(clientId, UUID.randomUUID().toString()) }

        val body = introspect(token, resourceServer).jsonBody()

        assertEquals(JsonPrimitive(true), body["active"])
        assertEquals("Bearer", body.getValue("token_type").jsonPrimitive.content)
        assertEquals(clientId, body.getValue("client_id").jsonPrimitive.content)
        assertEquals(playerUniqueId, body.getValue("sub").jsonPrimitive.content)
        assertEquals("Steve", body.getValue("username").jsonPrimitive.content)
    }

    @Test
    @DisplayName("A legacy access token with typ JWT stays active during the transition")
    fun legacyAccessTokenIsActive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val token = JwtTestSupport.signLegacy("token") {
            withClientClaims(resourceServer.client.clientId, UUID.randomUUID().toString())
        }

        val body = introspect(token, resourceServer).jsonBody()

        assertEquals(JsonPrimitive(true), body["active"])
        assertEquals("Bearer", body.getValue("token_type").jsonPrimitive.content)
    }

    @Test
    @DisplayName("A refresh token is active and omits token_type")
    fun refreshTokenOmitsTokenType() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val token = JwtTestSupport.sign(TokenKind.REFRESH) {
            withClientClaims(resourceServer.client.clientId, UUID.randomUUID().toString())
        }

        val body = introspect(token, resourceServer).jsonBody()

        assertEquals(JsonPrimitive(true), body["active"])
        assertFalse(body.containsKey("token_type"))
    }

    @Test
    @DisplayName("A service token is active as Bearer and introspection does not update lastUsedAt")
    fun serviceTokenIsActiveWithoutUsageUpdate() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val (token, tokenId, _) = issueServiceToken()

        val body = introspect(token, resourceServer).jsonBody()

        assertEquals(JsonPrimitive(true), body["active"])
        assertEquals("Bearer", body.getValue("token_type").jsonPrimitive.content)
        val stored = ServiceAccountTokenRepository.findByTokenId(tokenId).getOrElse { error("Not found: $it") }
        assertNull(stored.lastUsedAt)
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
    @DisplayName("A reissued service token is inactive")
    fun reissuedServiceTokenIsInactive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val (token, _, accountId) = issueServiceToken()
        // サービストークンの再発行で旧トークンが失効する
        ServiceAccountTokenRepository.revokeAndCreate(accountId, UUID.randomUUID().toString(), "hash2", "console")
            .getOrElse { error("Failed to reissue: $it") }

        assertInactive(introspect(token, resourceServer).jsonBody())
    }

    @Test
    @DisplayName("A token of a deleted client is inactive")
    fun tokenOfDeletedClientIsInactive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val deleted = registerClient(ClientType.PUBLIC)
        val token = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClientClaims(deleted.client.clientId, UUID.randomUUID().toString())
        }
        OAuthClientRepository.delete(deleted.client.clientId).getOrElse { error("Failed to delete: $it") }

        assertInactive(introspect(token, resourceServer).jsonBody())
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
    @DisplayName("A token is inactive when its revocation state cannot be read")
    fun unreadableStateIsInactive() {
        // MockKの解除はスレッドローカルで管理されるため、コルーチンの外（同じスレッド）で設定と解除を行う
        mockkObject(RevokedTokenRepository)
        try {
            coEvery { RevokedTokenRepository.isRevoked(any()) } throws SQLException("database is down")

            oauthTestApplication {
                val resourceServer = registerResourceServer()
                val token = JwtTestSupport.sign(TokenKind.ACCESS) {
                    withClientClaims(resourceServer.client.clientId, UUID.randomUUID().toString())
                }

                assertInactive(introspect(token, resourceServer).jsonBody())
            }
        } finally {
            unmockkObject(RevokedTokenRepository)
        }
    }

    @Test
    @DisplayName("Revoked and expired tokens are inactive")
    fun revokedAndExpiredTokensAreInactive() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val clientId = resourceServer.client.clientId
        val revokedTokenId = UUID.randomUUID().toString()
        val revoked = JwtTestSupport.sign(TokenKind.ACCESS) { withClientClaims(clientId, revokedTokenId) }
        val expired = JwtTestSupport.sign(TokenKind.ACCESS, Duration.ofMinutes(-1)) {
            withClientClaims(clientId, UUID.randomUUID().toString())
        }
        RevokedTokenRepository.revoke(revokedTokenId, TokenType.ACCESS_TOKEN, clientId, LocalDateTime.now().plusMinutes(5))
            .getOrElse { error("Failed to revoke: $it") }

        // RFC 7662 Section 2.2: 無効なトークンでもエラーではなく200で応答する
        val revokedResponse = introspect(revoked, resourceServer)
        assertEquals(HttpStatusCode.OK, revokedResponse.status)
        assertInactive(revokedResponse.jsonBody())
        assertInactive(introspect(expired, resourceServer).jsonBody())
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

    @Test
    @DisplayName("An authorized client can introspect a token of another client")
    fun authorizedClientIntrospectsAnotherClientsToken() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val relyingParty = registerClient(ClientType.PUBLIC)
        val token = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClientClaims(relyingParty.client.clientId, UUID.randomUUID().toString())
        }

        val body = introspect(token, resourceServer).jsonBody()

        assertEquals(JsonPrimitive(true), body["active"])
        assertEquals(relyingParty.client.clientId, body.getValue("client_id").jsonPrimitive.content)
        assertEquals(relyingParty.client.clientId, body.getValue("aud").jsonPrimitive.content)
    }

    @Test
    @DisplayName("A wrong secret returns 401 with or without the permission")
    fun wrongSecretIsUnauthorized() = oauthTestApplication {
        val authorized = registerResourceServer()
        val unauthorized = registerClient(ClientType.CONFIDENTIAL)
        val token = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClientClaims(authorized.client.clientId, UUID.randomUUID().toString())
        }

        listOf(authorized, unauthorized).forEach { caller ->
            val response = introspect(token, caller.client.clientId, "wrong-secret")

            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("invalid_client", response.jsonBody().getValue("error").jsonPrimitive.content)
        }
    }

    @Test
    @DisplayName("Withdrawing the permission applies to the next request")
    fun withdrawingThePermissionAppliesImmediately() = oauthTestApplication {
        val resourceServer = registerResourceServer()
        val token = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClientClaims(resourceServer.client.clientId, UUID.randomUUID().toString())
        }
        assertEquals(HttpStatusCode.OK, introspect(token, resourceServer).status)

        OAuthClientRepository.setIntrospectionAllowed(resourceServer.client.clientId, allowed = false)
            .getOrElse { error("Failed to withdraw the permission: $it") }

        assertEquals(HttpStatusCode.Forbidden, introspect(token, resourceServer).status)
    }
}
