package party.morino.mineauth.core.web.ratelimit

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RateLimiterのテスト
 * 時刻を注入して、回数の上限・回復・キーの追い出しを確認する
 */
class RateLimiterTest {
    private var now = 0L

    private fun limiter(requestsPerMinute: Int, maxKeys: Int = 100) =
        RateLimiter(requestsPerMinute, clock = { now }, maxKeys = maxKeys)

    @Test
    @DisplayName("Allows a retry exactly when Retry-After says so")
    fun refillsOverTime() {
        val limiter = limiter(requestsPerMinute = 2)
        repeat(2) { limiter.tryAcquire("a") }

        now = 29_999
        assertEquals(RateLimited(1).left(), limiter.tryAcquire("a"))
        now = 30_000
        assertEquals(Unit.right(), limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a").isLeft())
    }
}
