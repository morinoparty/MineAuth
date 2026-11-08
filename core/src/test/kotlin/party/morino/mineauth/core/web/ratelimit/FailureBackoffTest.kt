package party.morino.mineauth.core.web.ratelimit

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * FailureBackoffのテスト
 * 時刻を注入して、猶予・倍増・上限・リセット・放置による忘却を確認する
 */
class FailureBackoffTest {
    private var now = 0L

    private fun backoff(freeFailures: Int = 2, maxKeys: Int = 100) = FailureBackoff(
        freeFailures = freeFailures,
        baseDelay = 1.seconds,
        maxDelay = 8.seconds,
        idleExpiry = 60.seconds,
        clock = { now },
        maxKeys = maxKeys
    )

    /** ブロックが解けるまで時刻を進めてから、失敗を1回記録する */
    private fun FailureBackoff.failAfterBlock(key: String) {
        now += (retryAfterSeconds(key) ?: 0) * 1_000
        recordFailure(key)
    }

    @Test
    @DisplayName("Doubles the delay up to the cap")
    fun doublesUpToCap() {
        val backoff = backoff(freeFailures = 0)

        val delays = (1..5).map {
            backoff.failAfterBlock("a")
            backoff.retryAfterSeconds("a")
        }

        assertEquals(listOf<Long?>(1, 2, 4, 8, 8), delays)
    }

    @Test
    @DisplayName("Stays at the cap when the shift would overflow")
    fun guardsShiftOverflow() {
        val backoff = backoff(freeFailures = 0)

        // 2^63を超える回数でも、あふれて負の時間にならない
        repeat(200) { backoff.recordFailure("a") }

        assertEquals(8, backoff.retryAfterSeconds("a"))
    }

    @Test
    @DisplayName("Evicts the least recently used key at maxKeys")
    fun evictsLeastRecentlyUsedKey() {
        val backoff = backoff(freeFailures = 0, maxKeys = 2)
        backoff.recordFailure("a")
        backoff.recordFailure("b")
        // aを確認するとbが最も長く使われていないキーになり、cの追加で追い出される
        backoff.retryAfterSeconds("a")
        backoff.recordFailure("c")

        assertEquals(1, backoff.retryAfterSeconds("a"))
        assertNull(backoff.retryAfterSeconds("b"))
    }
}
