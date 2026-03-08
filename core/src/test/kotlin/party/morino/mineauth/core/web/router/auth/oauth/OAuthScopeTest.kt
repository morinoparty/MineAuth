package party.morino.mineauth.core.web.router.auth.oauth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OAuthScopeのテスト
 * スコープ検証の純粋関数をテストする
 */
class OAuthScopeTest {

    @Nested
    @DisplayName("isValid")
    inner class IsValidTest {

        @Test
        @DisplayName("Valid single scope")
        fun validSingleScope() {
            assertTrue(OAuthScope.isValid("openid"))
            assertTrue(OAuthScope.isValid("profile"))
            assertTrue(OAuthScope.isValid("plugin"))
        }

        @Test
        @DisplayName("Valid multiple scopes")
        fun validMultipleScopes() {
            assertTrue(OAuthScope.isValid("openid profile"))
            assertTrue(OAuthScope.isValid("openid profile email plugin roles"))
        }

        @Test
        @DisplayName("Invalid scope rejected")
        fun invalidScopeRejected() {
            assertFalse(OAuthScope.isValid("invalid_scope"))
            assertFalse(OAuthScope.isValid("openid admin"))
        }

        @Test
        @DisplayName("Empty scope is malformed")
        fun emptyScopeIsMalformed() {
            assertFalse(OAuthScope.isValid(""))
            assertFalse(OAuthScope.isValid("   "))
        }
    }

    @Nested
    @DisplayName("normalize")
    inner class NormalizeTest {

        @Test
        @DisplayName("Removes extra whitespace")
        fun removesExtraWhitespace() {
            assertEquals("openid profile", OAuthScope.normalize("openid  profile"))
            assertEquals("openid profile email", OAuthScope.normalize("  openid   profile  email  "))
        }

        @Test
        @DisplayName("Already normalized stays unchanged")
        fun alreadyNormalizedUnchanged() {
            assertEquals("openid profile", OAuthScope.normalize("openid profile"))
        }
    }

    @Nested
    @DisplayName("findInvalidScopes")
    inner class FindInvalidScopesTest {

        @Test
        @DisplayName("Returns empty list for valid scopes")
        fun returnsEmptyForValidScopes() {
            val result = OAuthScope.findInvalidScopes("openid profile")
            assertEquals(emptyList(), result)
        }

        @Test
        @DisplayName("Returns invalid scope names")
        fun returnsInvalidScopeNames() {
            val result = OAuthScope.findInvalidScopes("openid admin write")
            assertEquals(listOf("admin", "write"), result)
        }
    }
}
