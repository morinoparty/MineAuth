package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.components.auth.ClientData
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AuthorizationRequestValidatorのテスト
 * クライアントの取得を差し替えられるので、DBなしで検証順と応答方法の分岐を確認する
 */
class AuthorizationRequestValidatorTest {
    private val client = ClientData.PublicClientData(
        clientId = "client",
        clientName = "Sample",
        redirectUris = listOf("https://app.example.com/callback"),
    )

    private val validParams = mapOf(
        "response_type" to "code",
        "client_id" to "client",
        "redirect_uri" to "https://app.example.com/callback",
        "scope" to "openid profile",
        "state" to "state-1",
        "code_challenge" to "challenge",
        "code_challenge_method" to "S256",
    )

    /** 有効なパラメータの一部を上書きしたParametersを作る（nullはキーごと削除する） */
    private fun params(vararg overrides: Pair<String, String?>): Parameters {
        val merged = validParams + overrides
        return parametersOf(merged.mapNotNull { (key, value) -> value?.let { key to listOf(it) } }.toMap())
    }

    private fun validate(vararg overrides: Pair<String, String?>): Either<AuthorizeError, ValidatedAuthorizeRequest> =
        AuthorizationRequestValidator.validateAuthorizationRequest(params(*overrides)) { id ->
            client.takeIf { it.clientId == id }
        }

    /** リダイレクトで通知されるエラーであることを確認して返す */
    private fun redirectError(vararg overrides: Pair<String, String?>): AuthorizeError.RedirectWithError =
        assertIs<AuthorizeError.RedirectWithError>(validate(*overrides).leftOrNull())

    /** 有効なパラメータのうち1つを、指定した複数の値を持つものに置き換えて検証する */
    private fun validateRepeated(name: String, vararg values: String): Either<AuthorizeError, ValidatedAuthorizeRequest> {
        val repeated = validParams.mapValues { (_, value) -> listOf(value) } + (name to values.toList())
        return AuthorizationRequestValidator.validateAuthorizationRequest(parametersOf(repeated)) { id ->
            client.takeIf { it.clientId == id }
        }
    }

    @Nested
    @DisplayName("Client and redirect_uri")
    inner class ClientAndRedirectUriTest {

        @Test
        @DisplayName("Missing client_id shows an error")
        fun missingClientIdShowsError() {
            assertIs<AuthorizeError.ShowError>(validate("client_id" to null).leftOrNull())
        }

        @Test
        @DisplayName("Missing redirect_uri shows an error")
        fun missingRedirectUriShowsError() {
            assertIs<AuthorizeError.ShowError>(validate("redirect_uri" to null).leftOrNull())
        }

        @Test
        @DisplayName("Unknown client shows an error")
        fun unknownClientShowsError() {
            assertIs<AuthorizeError.ShowError>(validate("client_id" to "other").leftOrNull())
        }

        @Test
        @DisplayName("Unregistered redirect_uri shows an error")
        fun unregisteredRedirectUriShowsError() {
            assertIs<AuthorizeError.ShowError>(validate("redirect_uri" to "https://evil.example").leftOrNull())
        }

        @Test
        @DisplayName("Checks the redirect_uri before any other parameter")
        fun checksRedirectUriFirst() {
            // 他のパラメータも不正だが、信頼できないredirect_uriへ通知してはならない
            val error = validate(
                "redirect_uri" to "https://evil.example",
                "prompt" to "none",
                "response_type" to "token",
                "request" to "jwt",
            ).leftOrNull()

            assertIs<AuthorizeError.ShowError>(error)
        }
    }

    @Nested
    @DisplayName("Unsupported parameters")
    inner class UnsupportedParametersTest {

        @Test
        @DisplayName("request is rejected with state")
        fun requestIsRejected() {
            val error = redirectError("request" to "jwt")

            assertEquals(OAuthErrorCode.REQUEST_NOT_SUPPORTED, error.code)
            assertEquals("state-1", error.state)
            assertEquals("https://app.example.com/callback", error.redirectUri)
        }

        @Test
        @DisplayName("request_uri is rejected")
        fun requestUriIsRejected() {
            assertEquals(OAuthErrorCode.REQUEST_URI_NOT_SUPPORTED, redirectError("request_uri" to "https://x").code)
        }

        @Test
        @DisplayName("request is checked before response_type")
        fun requestCheckedBeforeResponseType() {
            val error = redirectError("request" to "jwt", "response_type" to null)

            assertEquals(OAuthErrorCode.REQUEST_NOT_SUPPORTED, error.code)
        }
    }

    @Nested
    @DisplayName("prompt")
    inner class PromptTest {

        @Test
        @DisplayName("none returns login_required")
        fun noneReturnsLoginRequired() {
            val error = redirectError("prompt" to "none")

            assertEquals(OAuthErrorCode.LOGIN_REQUIRED, error.code)
            assertEquals("state-1", error.state)
        }

        @Test
        @DisplayName("none with another value returns invalid_request")
        fun noneWithOtherValueReturnsInvalidRequest() {
            assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectError("prompt" to "none login").code)
        }

        @Test
        @DisplayName("Interactive values keep showing the page")
        fun interactiveValuesAreAccepted() {
            listOf("login", "consent", "select_account", "login consent").forEach { prompt ->
                assertTrue(validate("prompt" to prompt).isRight(), prompt)
            }
        }
    }

    @Nested
    @DisplayName("Repeated parameters")
    inner class RepeatedParametersTest {

        /** 重複を指摘するinvalid_requestのリダイレクトであることを確認して返す */
        private fun repeatedError(name: String, vararg values: String): AuthorizeError.RedirectWithError {
            val error = assertIs<AuthorizeError.RedirectWithError>(validateRepeated(name, *values).leftOrNull())
            assertEquals(OAuthErrorCode.INVALID_REQUEST, error.code, name)
            assertEquals("Parameter must not be repeated: $name", error.description)
            return error
        }

        @Test
        @DisplayName("Repeated client_id or redirect_uri shows an error and never redirects")
        fun repeatedClientIdOrRedirectUriShowsError() {
            val redirectUri = "https://app.example.com/callback"

            assertIs<AuthorizeError.ShowError>(validateRepeated("client_id", "client", "client").leftOrNull())
            assertIs<AuthorizeError.ShowError>(validateRepeated("redirect_uri", redirectUri, redirectUri).leftOrNull())
            assertIs<AuthorizeError.ShowError>(validateRepeated("redirect_uri", redirectUri, "https://evil.example").leftOrNull())
        }

        @Test
        @DisplayName("prompt=login with prompt=none does not show the login page")
        fun promptLoginWithPromptNone() {
            assertEquals("state-1", repeatedError("prompt", "login", "none").state)
        }

        @Test
        @DisplayName("Empty request with a real one is not ignored")
        fun emptyRequestWithRealOne() {
            repeatedError("request", "", "eyJhbGciOiJub25lIn0.e30.")
            repeatedError("request_uri", "", "https://evil.example/ro")
        }

        @Test
        @DisplayName("Other repeated parameters return invalid_request")
        fun otherRepeatedParameters() {
            val names = listOf("response_type", "scope", "code_challenge", "code_challenge_method", "nonce", "username", "password")
            names.forEach { name -> repeatedError(name, "a", "a") }
        }

        @Test
        @DisplayName("Repeated state is left out of the error redirect")
        fun repeatedStateIsLeftOut() {
            assertNull(repeatedError("state", "state-1", "state-2").state)
        }

        @Test
        @DisplayName("Repetition is reported before the other checks")
        fun repetitionComesFirst() {
            // 値の検証より先に重複を拒否するので、requestの拒否より優先される
            val params = validParams.mapValues { (_, value) -> listOf(value) } +
                mapOf("request" to listOf("jwt"), "nonce" to listOf("a", "b"))
            val error = AuthorizationRequestValidator.validateAuthorizationRequest(parametersOf(params)) { client }

            assertEquals(OAuthErrorCode.INVALID_REQUEST, assertIs<AuthorizeError.RedirectWithError>(error.leftOrNull()).code)
        }

        @Test
        @DisplayName("Ordinary request and unread repeated parameters are unaffected")
        fun ordinaryRequestIsUnaffected() {
            assertTrue(validate().isRight())
            // 検証で読み取らないパラメータは未知の拡張と同じく無視する
            assertTrue(validateRepeated("login_hint", "a", "b").isRight())
        }

        @Test
        @DisplayName("Whitespace-only values count as omitted without disturbing the check order")
        fun whitespaceOnlyValuesCountAsOmitted() {
            assertIs<AuthorizeError.ShowError>(validate("client_id" to " ").leftOrNull())
            assertTrue(validate("request" to " ", "prompt" to " ").isRight())
            // 空白のresponse_typeはstateの検証より先に欠落として報告する
            val error = redirectError("response_type" to " ", "state" to " ")
            assertEquals("Missing required parameter: response_type", error.description)
            assertNull(error.state)
        }

        @Test
        @DisplayName("Password of spaces passes the validation")
        fun passwordOfSpacesPasses() {
            assertTrue(validate("username" to "steve", "password" to "   ").isRight())
        }
    }

    @Nested
    @DisplayName("Required parameters")
    inner class RequiredParametersTest {

        @Test
        @DisplayName("Missing response_type returns invalid_request")
        fun missingResponseType() {
            assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectError("response_type" to null).code)
        }

        @Test
        @DisplayName("Other response_type returns unsupported_response_type")
        fun unsupportedResponseType() {
            assertEquals(OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE, redirectError("response_type" to "token").code)
        }

        @Test
        @DisplayName("Missing state returns invalid_request without state")
        fun missingState() {
            val error = redirectError("state" to null)

            assertEquals(OAuthErrorCode.INVALID_REQUEST, error.code)
            assertNull(error.state)
        }

        @Test
        @DisplayName("Missing scope returns invalid_scope")
        fun missingScope() {
            assertEquals(OAuthErrorCode.INVALID_SCOPE, redirectError("scope" to null).code)
        }

        @Test
        @DisplayName("Parameters without a value count as omitted")
        fun emptyParametersCountAsOmitted() {
            // RFC 6749 Section 3.1: 値の無いパラメータは省略として扱う
            assertIs<AuthorizeError.ShowError>(validate("client_id" to "").leftOrNull())
            assertNull(redirectError("state" to "").state)
            assertEquals(OAuthErrorCode.INVALID_SCOPE, redirectError("scope" to "").code)
            assertNull(validate("nonce" to "").getOrNull()?.nonce)
        }
    }

    @Nested
    @DisplayName("scope")
    inner class ScopeTest {

        @Test
        @DisplayName("Unknown value in an OIDC request is dropped")
        fun unknownValueDroppedWithOpenid() {
            val request = assertNotNull(validate("scope" to "openid foo offline_access").getOrNull())

            assertEquals("openid offline_access", request.scope)
        }

        @Test
        @DisplayName("Unknown value without openid returns invalid_scope")
        fun unknownValueRejectedWithoutOpenid() {
            val error = redirectError("scope" to "profile foo")

            assertEquals(OAuthErrorCode.INVALID_SCOPE, error.code)
            assertEquals("Invalid scope(s): foo", error.description)
        }
    }

    @Nested
    @DisplayName("PKCE")
    inner class PkceTest {

        @Test
        @DisplayName("Missing code_challenge returns invalid_request")
        fun missingCodeChallenge() {
            assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectError("code_challenge" to null).code)
        }

        @Test
        @DisplayName("Missing or plain code_challenge_method returns invalid_request")
        fun unsupportedCodeChallengeMethod() {
            assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectError("code_challenge_method" to null).code)
            assertEquals(OAuthErrorCode.INVALID_REQUEST, redirectError("code_challenge_method" to "plain").code)
        }
    }

    @Test
    @DisplayName("Valid request returns the validated values")
    fun validRequest() {
        val request = assertNotNull(validate("nonce" to "n-1").getOrNull())

        assertEquals("client", request.clientId)
        assertEquals("Sample", request.clientName)
        assertEquals("https://app.example.com/callback", request.redirectUri)
        assertEquals("state-1", request.state)
        assertEquals("openid profile", request.scope)
        assertEquals("challenge", request.codeChallenge)
        assertEquals("S256", request.codeChallengeMethod)
        assertEquals("n-1", request.nonce)
    }

    @Nested
    @DisplayName("isLoginSubmission")
    inner class IsLoginSubmissionTest {

        @Test
        @DisplayName("Credential fields mark a login submission")
        fun credentialFieldsMarkLogin() {
            assertTrue(AuthorizationRequestValidator.isLoginSubmission(params("username" to "steve")))
            assertTrue(AuthorizationRequestValidator.isLoginSubmission(params("password" to "secret")))
        }

        @Test
        @DisplayName("Request without credentials is an authorization request")
        fun requestWithoutCredentialsIsNotLogin() {
            assertFalse(AuthorizationRequestValidator.isLoginSubmission(params()))
        }
    }
}
