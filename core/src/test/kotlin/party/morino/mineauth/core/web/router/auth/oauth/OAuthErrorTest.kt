package party.morino.mineauth.core.web.router.auth.oauth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * OAuthErrorのテスト
 * RFC 6749 Section 5.2 準拠のエラーレスポンス生成をテスト
 */
class OAuthErrorTest {

    @Nested
    @DisplayName("OAuthErrorCode")
    inner class OAuthErrorCodeTest {

        @Test
        @DisplayName("Error code values are RFC 6749 compliant")
        fun errorCodeValuesAreRfc6749Compliant() {
            // RFC 6749で定義されたエラーコードが正しいことを確認
            assertEquals("invalid_request", OAuthErrorCode.INVALID_REQUEST.code)
            assertEquals("invalid_client", OAuthErrorCode.INVALID_CLIENT.code)
            assertEquals("invalid_grant", OAuthErrorCode.INVALID_GRANT.code)
            assertEquals("unauthorized_client", OAuthErrorCode.UNAUTHORIZED_CLIENT.code)
            assertEquals("unsupported_grant_type", OAuthErrorCode.UNSUPPORTED_GRANT_TYPE.code)
            assertEquals("invalid_scope", OAuthErrorCode.INVALID_SCOPE.code)
        }

        @Test
        @DisplayName("toResponse creates correct error response")
        fun toResponseCreatesCorrectErrorResponse() {
            val description = "Missing required parameter"
            val response = OAuthErrorCode.INVALID_REQUEST.toResponse(description)

            assertEquals("invalid_request", response.error)
            assertEquals(description, response.errorDescription)
        }

        @Test
        @DisplayName("toResponse with null description")
        fun toResponseWithNullDescription() {
            val response = OAuthErrorCode.INVALID_GRANT.toResponse()

            assertEquals("invalid_grant", response.error)
            assertNull(response.errorDescription)
        }
    }

    @Nested
    @DisplayName("OAuthErrorResponse")
    inner class OAuthErrorResponseTest {

        @Test
        @DisplayName("Create response with error and description")
        fun createResponseWithErrorAndDescription() {
            val response = OAuthErrorResponse(
                error = "invalid_client",
                errorDescription = "Client authentication failed"
            )

            assertEquals("invalid_client", response.error)
            assertEquals("Client authentication failed", response.errorDescription)
        }

        @Test
        @DisplayName("Create response with error only")
        fun createResponseWithErrorOnly() {
            val response = OAuthErrorResponse(error = "invalid_scope")

            assertEquals("invalid_scope", response.error)
            assertNull(response.errorDescription)
        }
    }
}
