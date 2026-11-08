package party.morino.mineauth.core.model

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * TokenKindの判定関数のテスト
 * 明示的なtyp、移行期間中の旧トークン、判定不能なトークンの分岐を確認する
 */
class TokenKindTest {

    @Test
    @DisplayName("Explicit typ with a conflicting or missing claim is unknown")
    fun explicitTypWithConflictingClaim() {
        assertEquals(TokenKind.UNKNOWN, TokenKind.classify("at+jwt", "refresh_token", null))
        assertEquals(TokenKind.UNKNOWN, TokenKind.classify("rt+jwt", null, "subject"))
    }

    @Test
    @DisplayName("Legacy typ JWT is classified by the token_type claim")
    fun legacyTypIsClassifiedByClaim() {
        assertEquals(TokenKind.ACCESS, TokenKind.classify("JWT", "token", null))
        assertEquals(TokenKind.REFRESH, TokenKind.classify("JWT", "refresh_token", null))
        assertEquals(TokenKind.SERVICE, TokenKind.classify("JWT", "service_token", null))
    }

}
