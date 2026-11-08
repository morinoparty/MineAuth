package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.left
import arrow.core.right
import io.ktor.http.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * readSingleParameterのテスト
 * RFC 6749 Section 3.2 の空値と重複の扱いを確認する
 */
class OAuthParametersTest {

    private val repeatedCode = OAuthParameterFailure(
        OAuthErrorCode.INVALID_REQUEST,
        "Parameter must not be repeated: code"
    ).left()

    @Test
    @DisplayName("Treats empty and whitespace values as absent")
    fun blankValueIsAbsent() {
        assertEquals(null.right(), parametersOf("code", "").readSingleParameter("code"))
        assertEquals(null.right(), parametersOf("code", "   ").readSingleParameter("code"))
    }

    @Test
    @DisplayName("Rejects a repeated parameter even when one value is empty")
    fun rejectsRepeatedParameterWithEmptyValue() {
        assertEquals(repeatedCode, parametersOf("code", listOf("", "b")).readSingleParameter("code"))
    }
}
