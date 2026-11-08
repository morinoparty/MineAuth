package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import io.ktor.http.Parameters
import io.ktor.http.parametersOf
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.components.auth.ClientData
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    }

    @Nested
    @DisplayName("Required parameters")
    inner class RequiredParametersTest {

        @Test
        @DisplayName("Other response_type returns unsupported_response_type")
        fun unsupportedResponseType() {
            assertEquals(OAuthErrorCode.UNSUPPORTED_RESPONSE_TYPE, redirectError("response_type" to "token").code)
        }

        @Test
        @DisplayName("Missing scope returns invalid_scope")
        fun missingScope() {
            assertEquals(OAuthErrorCode.INVALID_SCOPE, redirectError("scope" to null).code)
        }
    }
}
