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
    @DisplayName("Explicit typ with matching claim gives that kind")
    fun explicitTypWithMatchingClaim() {
        assertEquals(TokenKind.ACCESS, TokenKind.classify("at+jwt", "token", null))
        assertEquals(TokenKind.REFRESH, TokenKind.classify("rt+jwt", "refresh_token", null))
        assertEquals(TokenKind.SERVICE, TokenKind.classify("service+jwt", "service_token", null))
    }

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

    @Test
    @DisplayName("Typ JWT with sub and no token_type is an ID token")
    fun idTokenRequiresSubAndNoTokenType() {
        assertEquals(TokenKind.ID, TokenKind.classify("JWT", null, "subject"))
        assertEquals(TokenKind.UNKNOWN, TokenKind.classify("JWT", null, null))
    }

    @Test
    @DisplayName("Unknown claim, unknown typ and missing typ are unknown")
    fun unrecognizedTokensAreUnknown() {
        assertEquals(TokenKind.UNKNOWN, TokenKind.classify("JWT", "something_else", "subject"))
        assertEquals(TokenKind.UNKNOWN, TokenKind.classify("dpop+jwt", "token", null))
        assertEquals(TokenKind.UNKNOWN, TokenKind.classify(null, "token", null))
    }
}
