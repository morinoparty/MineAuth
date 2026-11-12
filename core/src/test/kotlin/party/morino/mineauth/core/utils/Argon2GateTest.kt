package party.morino.mineauth.core.utils

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Argon2Gateのテスト
 * 実際のArgon2は使わず、遅い計算の代わりに待機するブロックで同時実行数を確認する
 */
class Argon2GateTest {

    private fun source(lastOctet: Int) = ClientIpKey("203.0.113.$lastOctet")

    /**
     * 別のスレッドで、指定した接続元の検証を1つ実行したまま止めておく
     *
     * @param gate 対象のゲート
     * @param source 検証を要求する接続元
     * @param release 検証を終わらせる合図
     * @return 検証を実行しているスレッド
     */
    private fun holdVerification(gate: Argon2Gate, source: ClientIpKey, release: CountDownLatch): Thread {
        val holding = CountDownLatch(1)
        val holder = Thread {
            runBlocking {
                gate.withPermit(source) {
                    holding.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }
            }
        }
        holder.start()
        holding.await(10, TimeUnit.SECONDS)
        return holder
    }

    @Test
    @DisplayName("Runs no more than the permit count at once")
    fun limitsConcurrency() = runBlocking {
        val gate = Argon2Gate(permits = 2, acquireTimeout = 10.seconds)
        val running = AtomicInteger()
        val maxRunning = AtomicInteger()

        // 接続元ごとの枠に掛からないよう、別々の接続元から同時に計算を始め、同時に実行されていた数の最大を記録する
        val results = (1..8).map {
            async(Dispatchers.IO) {
                gate.withPermit(source(it)) {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), ::maxOf)
                    Thread.sleep(50)
                    running.decrementAndGet()
                }
            }
        }.awaitAll()

        assertTrue(results.all { it.isRight() })
        assertTrue(maxRunning.get() <= 2, "ran ${maxRunning.get()} at once")
    }

    @Test
    @DisplayName("One source runs at most two verifications and leaves the other permits free")
    fun limitsEachSource() = runBlocking {
        val gate = Argon2Gate(permits = 4, acquireTimeout = 200.milliseconds)
        val flooding = source(1)
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val gaveUp = CountDownLatch(8)
        val hashed = AtomicInteger()

        // 1つの接続元から10件の検証を同時に始め、計算を止めておく
        val flood = (1..10).map {
            async(Dispatchers.IO) {
                gate.withPermit(flooding) {
                    hashed.incrementAndGet()
                    started.countDown()
                    release.await(10, TimeUnit.SECONDS)
                }.also { result -> if (result.isLeft()) gaveUp.countDown() }
            }
        }
        started.await(10, TimeUnit.SECONDS)

        // 別の接続元の検証は、空いている許可ですぐに実行される
        assertEquals(1.right(), gate.withPermit(source(2)) { 1 })
        // 枠を待った残りの8件は、待機時間が過ぎると計算せずに諦める
        assertTrue(gaveUp.await(10, TimeUnit.SECONDS))
        release.countDown()
        flood.awaitAll()

        assertEquals(2, hashed.get())
    }

    @Test
    @DisplayName("Forgets a source once nothing from it is queued or running")
    fun forgetsIdleSources() = runBlocking {
        val gate = Argon2Gate(permits = 1, acquireTimeout = 50.milliseconds)

        // 完了と例外
        gate.withPermit(source(1)) { }
        runCatching { gate.withPermit(source(2)) { error("hash failed") } }
        assertEquals(0, gate.trackedSourceCount)

        // 待機時間切れとキャンセル（許可は別の接続元が持ち続ける）
        val release = CountDownLatch(1)
        val holder = holdVerification(gate, source(3), release)
        assertEquals(Argon2GateBusy.left(), gate.withPermit(source(4)) { })
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) { gate.withPermit(source(5)) { } }
        assertEquals(2, gate.trackedSourceCount)
        cancelled.cancel()
        cancelled.join()
        assertEquals(1, gate.trackedSourceCount)

        release.countDown()
        holder.join()
        assertEquals(0, gate.trackedSourceCount)
    }
}
