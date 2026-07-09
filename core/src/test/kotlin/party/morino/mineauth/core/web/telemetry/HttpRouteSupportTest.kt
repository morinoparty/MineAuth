package party.morino.mineauth.core.web.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * sanitizeAuthRouteのテスト
 * 認証セレクタ由来のセグメントが除去されることを検証する
 */
class HttpRouteSupportTest {

    @Test
    @DisplayName("Strips leading authenticate selector")
    fun stripsLeadingAuthenticateSelector() {
        val polluted = "/(authenticate user-oauth-token, service-oauth-token)/api/v1/plugins/{namespace}"
        assertEquals("/api/v1/plugins/{namespace}", sanitizeAuthRoute(polluted))
    }

    @Test
    @DisplayName("Strips authenticate selector in the middle of the path")
    fun stripsMiddleAuthenticateSelector() {
        val polluted = "/api/v1/commons/(authenticate service-oauth-token)/server/plugins"
        assertEquals("/api/v1/commons/server/plugins", sanitizeAuthRoute(polluted))
    }

    @Test
    @DisplayName("Strips default provider authenticate selector")
    fun stripsDefaultAuthenticateSelector() {
        val polluted = "/(authenticate \"default\")/hello"
        assertEquals("/hello", sanitizeAuthRoute(polluted))
    }

    @Test
    @DisplayName("Leaves routes without authenticate selectors unchanged")
    fun leavesCleanRouteUnchanged() {
        val clean = "/api/v1/plugins/vault/shops/{id}"
        assertEquals(clean, sanitizeAuthRoute(clean))
    }
}
