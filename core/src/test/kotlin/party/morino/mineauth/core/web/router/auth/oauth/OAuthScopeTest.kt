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
    @DisplayName("resolveGranted")
    inner class ResolveGrantedTest {

        @Test
        @DisplayName("Recognizes offline_access")
        fun recognizesOfflineAccess() {
            val granted = OAuthScope.resolveGranted("openid offline_access").getOrNull()

            assertEquals(setOf(OAuthScope.OPENID, OAuthScope.OFFLINE_ACCESS), granted)
        }

        @Test
        @DisplayName("Drops unknown values from an OIDC request")
        fun dropsUnknownValuesWithOpenid() {
            val granted = OAuthScope.resolveGranted("openid unknown profile").getOrNull()

            assertEquals(setOf(OAuthScope.OPENID, OAuthScope.PROFILE), granted)
        }

        @Test
        @DisplayName("Rejects a request with only unknown values")
        fun rejectsOnlyUnknownValues() {
            assertEquals(listOf("unknown"), OAuthScope.resolveGranted("unknown").leftOrNull()?.unknownValues)
        }

        @Test
        @DisplayName("Stays strict without openid")
        fun staysStrictWithoutOpenid() {
            assertEquals(listOf("unknown"), OAuthScope.resolveGranted("profile unknown").leftOrNull()?.unknownValues)
        }

        @Test
        @DisplayName("Rejects a blank scope")
        fun rejectsBlankScope() {
            assertEquals(emptyList(), OAuthScope.resolveGranted("   ").leftOrNull()?.unknownValues)
        }

        @Test
        @DisplayName("Formats the granted scope in request order without duplicates")
        fun formatsGrantedScope() {
            val granted = OAuthScope.resolveGranted("profile  openid profile unknown").getOrNull().orEmpty()

            assertEquals("profile openid", OAuthScope.format(granted))
        }

        @Test
        @DisplayName("Granted scope passes the refresh check")
        fun grantedScopePassesRefreshCheck() {
            // TokenRouterはリフレッシュ時に保存済みスコープをisValidで再検証する
            val granted = OAuthScope.resolveGranted("openid offline_access unknown").getOrNull().orEmpty()

            assertTrue(OAuthScope.isValid(OAuthScope.format(granted)))
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
