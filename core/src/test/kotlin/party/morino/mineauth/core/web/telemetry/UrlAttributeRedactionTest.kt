package party.morino.mineauth.core.web.telemetry

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * redactQueryValuesのテスト
 * url.queryにパラメーター名だけが残り、値が残らないことを検証する
 */
class UrlAttributeRedactionTest {

    @Test
    @DisplayName("Keeps parameter names and replaces every value")
    fun keepsNamesAndReplacesValues() {
        val query = "id_token_hint=eyJhbGciOiJSUzI1NiJ9.eyJlbWFpbCI6ImFAYi5jIn0.sig" +
            "&state=xyz&post_logout_redirect_uri=https%3A%2F%2Fapp.example%2F"

        assertEquals(
            "id_token_hint=REDACTED&state=REDACTED&post_logout_redirect_uri=REDACTED",
            redactQueryValues(query)
        )
    }
}
