package party.morino.mineauth.core.web.ratelimit

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * RateLimitKeysのテスト
 * 表記を変えても別のキーにならず、実在しない値でキーが増えないことを確認する
 */
class RateLimitKeysTest {

    @Test
    @DisplayName("A username is one key in any letter case")
    fun usernameIgnoresCase() {
        assertEquals(RateLimitKeys.username("steve"), RateLimitKeys.username("StEvE"))
        assertNotEquals(RateLimitKeys.username("steve"), RateLimitKeys.username("alex"))
    }

    @Test
    @DisplayName("Usernames longer than 64 characters share one key")
    fun longUsernamesShareKey() {
        assertNotEquals(RateLimitKeys.username("a".repeat(64)), RateLimitKeys.username("b".repeat(64)))
        assertEquals(RateLimitKeys.username("a".repeat(65)), RateLimitKeys.username("b".repeat(65)))
    }
}
