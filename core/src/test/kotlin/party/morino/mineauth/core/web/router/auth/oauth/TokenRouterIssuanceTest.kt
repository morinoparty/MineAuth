package party.morino.mineauth.core.web.router.auth.oauth

import com.auth0.jwt.JWT
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.model.grantId
import party.morino.mineauth.core.model.tokenKind
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * トークンエンドポイントが発行する実際のトークンのテスト
 * 発行したトークンが自分の種別のVerifierを通ることで、発行側と検証側の規則の一致を確認する（Issue #481）
 */
class TokenRouterIssuanceTest : OAuthRouteTestBase() {

    private val redirectUri = "https://rp.example.com/callback"

    // RFC 7636 Section 4.1 を満たす固定のcode_verifier
    private val codeVerifier = "a".repeat(43)

    @BeforeEach
    fun useTestSigningKey() {
        // 発行に使う鍵をKeyUtilsのキャッシュではなくテスト用の鍵に差し替える
        mockkObject(JwtProvider)
        every { JwtProvider.algorithm } returns JwtTestSupport.algorithm
    }

    @AfterEach
    fun restoreSigningKey() {
        unmockkObject(JwtProvider)
    }

    private fun codeChallenge(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    private suspend fun HttpResponse.tokenField(name: String): String = jsonBody().getValue(name).jsonPrimitive.content

    @Test
    @DisplayName("Authorization code grant issues tokens accepted by the verifier of their own kind")
    fun authorizationCodeGrantIssuesTypedTokens() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val playerId = UUID.randomUUID()
        val grantId = UUID.randomUUID().toString()
        val code = UUID.randomUUID().toString()
        OAuthRouter.authorizedData[code] = AuthorizedData(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = "openid",
            state = "state",
            codeChallenge = codeChallenge(),
            uniqueId = playerId,
            grantId = grantId
        )

        val response = client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
                append("client_id", clientId)
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val verifiers = JwtTestSupport.verifiers
        val accessToken = verifiers.bearer.verify(response.tokenField("access_token"))
        val refreshToken = verifiers.refreshToken.verify(response.tokenField("refresh_token"))
        val idToken = verifiers.idTokenHint.verify(response.tokenField("id_token"))
        assertEquals(TokenKind.ACCESS, accessToken.tokenKind())
        assertEquals(TokenKind.REFRESH, refreshToken.tokenKind())
        assertEquals(TokenKind.ID, idToken.tokenKind())

        // RFC 9068 Section 2.2: アクセストークンのsubはプレイヤーのUUID
        assertEquals(playerId.toString(), accessToken.subject)

        // grant_idはアクセストークンとリフレッシュトークンだけが持つ
        assertEquals(grantId, accessToken.grantId())
        assertEquals(grantId, refreshToken.grantId())
        assertNull(idToken.grantId())
    }

    @Test
    @DisplayName("Refresh grant accepts an unrevoked token and issues explicitly typed tokens")
    fun refreshGrantIssuesTypedTokens() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val playerId = UUID.randomUUID()
        val refreshToken = JwtTestSupport.sign(TokenKind.REFRESH) {
            withJWTId(UUID.randomUUID().toString())
            withClaim("client_id", clientId)
            withClaim("playerUniqueId", playerId.toString())
            withClaim("scope", "openid")
            withClaim("state", "state")
        }

        val response = client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", clientId)
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val accessToken = JWT.decode(response.tokenField("access_token"))
        assertEquals("at+jwt", accessToken.type)
        assertEquals("rt+jwt", JWT.decode(response.tokenField("refresh_token")).type)

        // subを持っていてもtoken_typeが優先され、ID Tokenとは判定されない
        assertEquals(playerId.toString(), accessToken.subject)
        assertEquals(TokenKind.ACCESS, accessToken.tokenKind())
    }
}
