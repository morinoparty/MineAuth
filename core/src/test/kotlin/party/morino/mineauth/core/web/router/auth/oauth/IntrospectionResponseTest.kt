package party.morino.mineauth.core.web.router.auth.oauth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * IntrospectionResponseのテスト
 * RFC 7662 Token Introspection レスポンスの生成をテスト
 */
class IntrospectionResponseTest {

    @Nested
    @DisplayName("IntrospectionResponse")
    inner class ResponseTest {

        @Test
        @DisplayName("Active response includes all metadata")
        fun activeResponseIncludesAllMetadata() {
            val response = IntrospectionResponse(
                active = true,
                scope = "openid profile",
                clientId = "test-client",
                username = "Steve",
                tokenType = "Bearer",
                exp = 1419356238,
                iat = 1419350238,
                sub = "550e8400-e29b-41d4-a716-446655440000",
                iss = "https://api.example.com",
                jti = "token-id-123"
            )

            assertTrue(response.active)
            assertEquals("openid profile", response.scope)
            assertEquals("test-client", response.clientId)
            assertEquals("Steve", response.username)
            assertEquals("Bearer", response.tokenType)
            assertEquals(1419356238, response.exp)
            assertEquals(1419350238, response.iat)
            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals("https://api.example.com", response.iss)
            assertEquals("token-id-123", response.jti)
        }

        @Test
        @DisplayName("Inactive response has no metadata")
        fun inactiveResponseHasNoMetadata() {
            val response = IntrospectionResponse(active = false)

            assertFalse(response.active)
            assertNull(response.scope)
            assertNull(response.clientId)
            assertNull(response.username)
            assertNull(response.tokenType)
            assertNull(response.exp)
            assertNull(response.iat)
            assertNull(response.sub)
        }
    }
}
