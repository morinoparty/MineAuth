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
    @DisplayName("Allows up to capacity and then limits with Retry-After")
    fun allowsUpToCapacity() {
        val limiter = limiter(requestsPerMinute = 2)

        assertEquals(Unit.right(), limiter.tryAcquire("a"))
        assertEquals(Unit.right(), limiter.tryAcquire("a"))
        // 2回/分なのでトークン1個の回復に30秒かかる
        assertEquals(RateLimited(30).left(), limiter.tryAcquire("a"))
    }

    @Test
    @DisplayName("Rounds Retry-After up to at least one second")
    fun roundsRetryAfterUp() {
        // 600回/分ではトークン1個が100ミリ秒で回復するが、Retry-Afterは整数の秒で返す
        val limiter = limiter(requestsPerMinute = 600)
        repeat(600) { limiter.tryAcquire("a") }

        assertEquals(RateLimited(1).left(), limiter.tryAcquire("a"))
    }

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

    @Test
    @DisplayName("Does not refill beyond capacity")
    fun doesNotRefillBeyondCapacity() {
        val limiter = limiter(requestsPerMinute = 2)
        limiter.tryAcquire("a")

        now = 3_600_000
        assertEquals(Unit.right(), limiter.tryAcquire("a"))
        assertEquals(Unit.right(), limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a").isLeft())
    }

    @Test
    @DisplayName("Keeps a separate bucket per key")
    fun separatesKeys() {
        val limiter = limiter(requestsPerMinute = 1)

        assertEquals(Unit.right(), limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("a").isLeft())
        assertEquals(Unit.right(), limiter.tryAcquire("b"))
    }

    @Test
    @DisplayName("Evicts the least recently used key at maxKeys")
    fun evictsLeastRecentlyUsedKey() {
        val limiter = limiter(requestsPerMinute = 1, maxKeys = 2)
        limiter.tryAcquire("a")
        limiter.tryAcquire("b")
        // aを使うとbが最も長く使われていないキーになり、cの追加で追い出される
        limiter.tryAcquire("a")
        limiter.tryAcquire("c")

        // aは覚えられたままで、bは新しいバケットで始まる
        assertTrue(limiter.tryAcquire("a").isLeft())
        assertEquals(Unit.right(), limiter.tryAcquire("b"))
    }
}
