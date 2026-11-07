package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.right
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.repository.ClientType
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GET / POST /oauth2/end_session のテスト
 * id_token_hintの検証、client_idの照合、GETとPOSTの一致を確認する（Issue #464 / #474）
 */
class EndSessionRouterTest : OAuthRouteTestBase() {

    private val redirectUri = "https://rp.example.com/logged-out"

    private fun idTokenFor(vararg audience: String, expiresIn: Duration = Duration.ofHours(1)): String =
        JwtTestSupport.sign(TokenKind.ID, expiresIn) {
            withSubject("00000000-0000-0000-0000-000000000001")
            withAudience(*audience)
        }

    // リダイレクトのレスポンス自体を確認するため、自動追従しないクライアントを使う
    private fun ApplicationTestBuilder.noRedirectClient(): HttpClient = createClient { followRedirects = false }

    private suspend fun HttpClient.getEndSession(vararg params: Pair<String, String>): HttpResponse =
        get("/oauth2/end_session") { params.forEach { (name, value) -> parameter(name, value) } }

    private suspend fun HttpClient.postEndSession(vararg params: Pair<String, String>): HttpResponse =
        submitForm(
            url = "/oauth2/end_session",
            formParameters = parameters { params.forEach { (name, value) -> append(name, value) } }
        )

    private suspend fun assertInvalidRequest(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_request", response.jsonBody().getValue("error").jsonPrimitive.content)
        assertNull(response.headers[HttpHeaders.Location])
    }

    @Test
    @DisplayName("GET and POST redirect with a valid id_token_hint and a registered URI")
    fun getAndPostRedirect() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val params = arrayOf(
            "id_token_hint" to idTokenFor(clientId),
            "post_logout_redirect_uri" to redirectUri,
            "state" to "abc123"
        )
        val httpClient = noRedirectClient()

        listOf(httpClient.getEndSession(*params), httpClient.postEndSession(*params)).forEach { response ->
            assertEquals(HttpStatusCode.Found, response.status)
            assertEquals("$redirectUri?state=abc123", response.headers[HttpHeaders.Location])
        }
    }

    @Test
    @DisplayName("An expired id_token_hint within the bound is accepted")
    fun expiredHintIsAccepted() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId

        val response = noRedirectClient().getEndSession(
            "id_token_hint" to idTokenFor(clientId, expiresIn = Duration.ofDays(-1)),
            "post_logout_redirect_uri" to redirectUri
        )

        assertEquals(HttpStatusCode.Found, response.status)
        assertEquals(redirectUri, response.headers[HttpHeaders.Location])
    }

    @Test
    @DisplayName("client_id not in the aud is rejected on GET and POST without a redirect")
    fun mismatchedClientIdIsRejected() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val params = arrayOf(
            "id_token_hint" to idTokenFor(clientId),
            "client_id" to "another-client",
            "post_logout_redirect_uri" to redirectUri
        )
        val httpClient = noRedirectClient()

        assertInvalidRequest(httpClient.getEndSession(*params))
        assertInvalidRequest(httpClient.postEndSession(*params))
    }

    @Test
    @DisplayName("client_id matching a later aud value selects that client")
    fun clientIdMatchesAnyAudienceValue() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId

        val response = noRedirectClient().getEndSession(
            "id_token_hint" to idTokenFor("another-client", clientId),
            "client_id" to clientId,
            "post_logout_redirect_uri" to redirectUri
        )

        assertEquals(HttpStatusCode.Found, response.status)
    }

    @Test
    @DisplayName("A token of another kind is not accepted as id_token_hint")
    fun otherKindIsRejectedAsHint() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val accessToken = JwtTestSupport.sign(TokenKind.ACCESS) { withAudience(clientId) }

        assertInvalidRequest(noRedirectClient().getEndSession("id_token_hint" to accessToken))
    }

    @Test
    @DisplayName("client_id alone does not authorize post_logout_redirect_uri")
    fun clientIdAloneDoesNotAuthorizeRedirect() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId

        assertInvalidRequest(
            noRedirectClient().getEndSession("client_id" to clientId, "post_logout_redirect_uri" to redirectUri)
        )
    }

    @Test
    @DisplayName("An unregistered post_logout_redirect_uri is rejected")
    fun unregisteredRedirectUriIsRejected() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId

        assertInvalidRequest(
            noRedirectClient().getEndSession(
                "id_token_hint" to idTokenFor(clientId),
                "post_logout_redirect_uri" to "https://evil.example.com/"
            )
        )
    }

    @Test
    @DisplayName("POST without parameters completes the logout")
    fun postWithoutParametersCompletesLogout() = oauthTestApplication {
        val response = noRedirectClient().postEndSession()

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.jsonBody().containsKey("message"))
    }

    @Test
    @DisplayName("selectClientId rejects an empty aud and falls back to the first value")
    fun selectClientIdBranches() {
        assertTrue(EndSessionRouter.selectClientId(emptyList(), "client").isLeft())
        assertEquals("first".right(), EndSessionRouter.selectClientId(listOf("first", "second"), null))
    }
}
