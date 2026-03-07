package party.morino.mineauth.core.repository

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * TokenTypeとRevokedTokenErrorのテスト
 * RFC 7009 Token Revocationに関連するデータ型の検証
 */
class TokenTypeTest {

    @Nested
    @DisplayName("fromValue")
    inner class FromValueTest {

        @Test
        @DisplayName("Returns ACCESS_TOKEN for 'access_token'")
        fun returnsAccessTokenForAccessTokenString() {
            val result = TokenType.fromValue("access_token")
            assertEquals(TokenType.ACCESS_TOKEN, result)
        }

        @Test
        @DisplayName("Returns REFRESH_TOKEN for 'refresh_token'")
        fun returnsRefreshTokenForRefreshTokenString() {
            val result = TokenType.fromValue("refresh_token")
            assertEquals(TokenType.REFRESH_TOKEN, result)
        }

        @Test
        @DisplayName("Returns null for unknown value")
        fun returnsNullForUnknownValue() {
            val result = TokenType.fromValue("unknown_token")
            assertNull(result)
        }

        @Test
        @DisplayName("Returns null for empty string")
        fun returnsNullForEmptyString() {
            val result = TokenType.fromValue("")
            assertNull(result)
        }

        @Test
        @DisplayName("Returns null for JWT claim value 'token'")
        fun returnsNullForTokenClaimValue() {
            // JWT内のアクセストークンはclaim値 "token" を使用するが、
            // fromValueは RFC準拠の "access_token" のみを受け付ける
            val result = TokenType.fromValue("token")
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("fromHint")
    inner class FromHintTest {

        @Test
        @DisplayName("Returns ACCESS_TOKEN for 'access_token' hint")
        fun returnsAccessTokenForAccessTokenHint() {
            val result = TokenType.fromHint("access_token")
            assertEquals(TokenType.ACCESS_TOKEN, result)
        }

        @Test
        @DisplayName("Returns REFRESH_TOKEN for 'refresh_token' hint")
        fun returnsRefreshTokenForRefreshTokenHint() {
            val result = TokenType.fromHint("refresh_token")
            assertEquals(TokenType.REFRESH_TOKEN, result)
        }

        @Test
        @DisplayName("Returns null for unknown hint")
        fun returnsNullForUnknownHint() {
            val result = TokenType.fromHint("id_token")
            assertNull(result)
        }

        @Test
        @DisplayName("Returns null for null hint")
        fun returnsNullForNullHint() {
            val result = TokenType.fromHint(null)
            assertNull(result)
        }
    }

    @Nested
    @DisplayName("TokenType values")
    inner class TokenTypeValuesTest {

        @Test
        @DisplayName("ACCESS_TOKEN value is 'access_token'")
        fun accessTokenValueIsCorrect() {
            assertEquals("access_token", TokenType.ACCESS_TOKEN.value)
        }

        @Test
        @DisplayName("REFRESH_TOKEN value is 'refresh_token'")
        fun refreshTokenValueIsCorrect() {
            assertEquals("refresh_token", TokenType.REFRESH_TOKEN.value)
        }
    }

    @Nested
    @DisplayName("RevokedTokenError")
    inner class RevokedTokenErrorTest {

        @Test
        @DisplayName("AlreadyRevoked is singleton")
        fun alreadyRevokedIsSingleton() {
            val error1 = RevokedTokenError.AlreadyRevoked
            val error2 = RevokedTokenError.AlreadyRevoked
            assertEquals(error1, error2)
        }

        @Test
        @DisplayName("DatabaseError contains message")
        fun databaseErrorContainsMessage() {
            val error = RevokedTokenError.DatabaseError("Connection failed")
            assertEquals("Connection failed", error.message)
        }
    }
}
