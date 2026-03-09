package party.morino.mineauth.core.web.components.auth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

/**
 * OIDCDiscoveryResponseのテスト
 * OIDC Discovery endpoint responses include new endpoints
 */
class OIDCDiscoveryResponseTest {

    @Nested
    @DisplayName("fromBaseUrl")
    inner class FromBaseUrlTest {

        @Test
        @DisplayName("Includes introspection endpoint")
        fun includesIntrospectionEndpoint() {
            val response = OIDCDiscoveryResponse.fromBaseUrl("https://api.example.com")
            assertEquals("https://api.example.com/oauth2/introspect", response.introspectionEndpoint)
        }

        @Test
        @DisplayName("Includes end session endpoint")
        fun includesEndSessionEndpoint() {
            val response = OIDCDiscoveryResponse.fromBaseUrl("https://api.example.com")
            assertEquals("https://api.example.com/oauth2/end_session", response.endSessionEndpoint)
        }

        @Test
        @DisplayName("Normalizes trailing slash in base URL")
        fun normalizesTrailingSlash() {
            val response = OIDCDiscoveryResponse.fromBaseUrl("https://api.example.com/")
            assertEquals("https://api.example.com/oauth2/introspect", response.introspectionEndpoint)
            assertEquals("https://api.example.com/oauth2/end_session", response.endSessionEndpoint)
        }

        @Test
        @DisplayName("Includes email scope when enabled")
        fun includesEmailScopeWhenEnabled() {
            val response = OIDCDiscoveryResponse.fromBaseUrl("https://api.example.com", emailEnabled = true)
            assertContains(response.scopesSupported, "email")
            assertContains(response.claimsSupported, "email")
            assertContains(response.claimsSupported, "email_verified")
        }

        @Test
        @DisplayName("Includes roles scope when enabled")
        fun includesRolesScopeWhenEnabled() {
            val response = OIDCDiscoveryResponse.fromBaseUrl("https://api.example.com", rolesEnabled = true)
            assertContains(response.scopesSupported, "roles")
            assertContains(response.claimsSupported, "roles")
        }
    }
}
