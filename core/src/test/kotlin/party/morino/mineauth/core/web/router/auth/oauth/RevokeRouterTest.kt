package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.request.forms.submitForm
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.RevokedTokenRepository
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * POST /oauth2/revoke のテスト
 * 失効が実際に記録されること（Issue #464の回帰防止）と、応答の分岐を確認する
 */
class RevokeRouterTest : OAuthRouteTestBase() {

    /**
     * クライアント自身のリフレッシュトークンを発行する
     */
    private fun refreshTokenOf(
        clientId: String,
        tokenId: String = UUID.randomUUID().toString(),
        expiresIn: Duration = Duration.ofDays(30)
    ): String = JwtTestSupport.sign(TokenKind.REFRESH, expiresIn) {
        withJWTId(tokenId)
        withAudience(clientId)
        withClaim("client_id", clientId)
        withClaim("playerUniqueId", UUID.randomUUID().toString())
        withClaim("scope", "openid")
        withClaim("state", "state")
    }

    private suspend fun ApplicationTestBuilder.revoke(token: String, owner: OAuthClientCreationResult) =
        client.submitForm(
            url = "/oauth2/revoke",
            formParameters = parameters {
                append("token", token)
                append("client_id", owner.client.clientId)
                append("client_secret", owner.clientSecret!!)
            }
        )

    private suspend fun ApplicationTestBuilder.refreshGrant(refreshToken: String, owner: OAuthClientCreationResult) =
        client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", owner.client.clientId)
                append("client_secret", owner.clientSecret!!)
            }
        )

    @Test
    @DisplayName("A revoked refresh token is refused at the token endpoint")
    fun revokedRefreshTokenIsRefused() = oauthTestApplication {
        val confidential = registerClient(ClientType.CONFIDENTIAL)
        val tokenId = UUID.randomUUID().toString()
        val refreshToken = refreshTokenOf(confidential.client.clientId, tokenId)

        val response = revoke(refreshToken, confidential)

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(RevokedTokenRepository.isRevoked(tokenId))
        val grant = refreshGrant(refreshToken, confidential)
        assertEquals(HttpStatusCode.BadRequest, grant.status)
        assertEquals("invalid_grant", grant.jsonBody().getValue("error").jsonPrimitive.content)
    }

    @Test
    @DisplayName("A failed database write returns server_error instead of 200")
    fun failedWriteReturnsServerError() = oauthTestApplication {
        val confidential = registerClient(ClientType.CONFIDENTIAL)
        // 失効の書き込みを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_tokens") }

        val response = revoke(refreshTokenOf(confidential.client.clientId), confidential)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("server_error", response.jsonBody().getValue("error").jsonPrimitive.content)
    }
}
