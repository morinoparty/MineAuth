package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.components.auth.TokenData
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RefreshTokenResponseCacheのテスト
 * reuse grace period（RFC 9700 Section 4.14.2）の主要分岐を検証する
 */
class RefreshTokenResponseCacheTest {

    // テスト用のトークンレスポンスを生成する
    private fun tokenData(marker: String) = TokenData(
        accessToken = "access-$marker",
        tokenType = "Bearer",
        expiresIn = 300,
        refreshToken = "refresh-$marker",
        idToken = null,
        scope = "openid"
    )

    @Test
    @DisplayName("Concurrent requests with same tokenId share a single issuance")
    fun concurrentRequestsShareSingleIssuance() = runTest {
        val cache = RefreshTokenResponseCache(gracePeriodMillis = 60_000L, clock = { 0L })
        val issueCount = AtomicInteger(0)
        // 発行処理を待機させて並行リクエストを再現するためのゲート
        val gate = CompletableDeferred<Unit>()

        // 4本の並行リクエスト（moripathの実例と同じ構図）
        val requests = (1..4).map {
            async {
                cache.getOrIssue("token-a") {
                    issueCount.incrementAndGet()
                    gate.await()
                    tokenData("winner").right()
                }
            }
        }
        gate.complete(Unit)
        val results = requests.awaitAll()

        // 発行処理は1回のみ実行され、全員が同じレスポンスを受け取る
        assertEquals(1, issueCount.get())
        results.forEach { result ->
            assertEquals(tokenData("winner").right(), result)
        }
    }

    @Test
    @DisplayName("Reuse within grace period returns cached response")
    fun reuseWithinGracePeriodReturnsCachedResponse() = runTest {
        var now = 0L
        val cache = RefreshTokenResponseCache(gracePeriodMillis = 60_000L, clock = { now })

        val first = cache.getOrIssue("token-a") { tokenData("first").right() }
        // grace期間内（59秒後）の再利用は同じレスポンスを返し、発行処理は実行されない
        now = 59_000L
        val second = cache.getOrIssue("token-a") { tokenData("second").right() }

        assertEquals(first, second)
    }

    @Test
    @DisplayName("Reuse after grace period runs issuance again")
    fun reuseAfterGracePeriodRunsIssuanceAgain() = runTest {
        var now = 0L
        val cache = RefreshTokenResponseCache(gracePeriodMillis = 60_000L, clock = { now })

        cache.getOrIssue("token-a") { tokenData("first").right() }
        // grace期間経過後はエントリが削除され、発行処理が再度実行される
        // （実際のフローではこの発行処理内の失効チェックがinvalid_grantを返す）
        now = 61_000L
        val second = cache.getOrIssue("token-a") { tokenData("second").right() }

        assertEquals(tokenData("second").right(), second)
    }

    @Test
    @DisplayName("Failure is not cached and next request retries")
    fun failureIsNotCachedAndNextRequestRetries() = runTest {
        val cache = RefreshTokenResponseCache(gracePeriodMillis = 60_000L, clock = { 0L })
        val failure = TokenRotationFailure(OAuthErrorCode.SERVER_ERROR, "Failed to rotate refresh token")

        val first = cache.getOrIssue("token-a") { failure.left() }
        // 失敗はキャッシュされず、次のリクエストで発行処理が再試行される
        val second = cache.getOrIssue("token-a") { tokenData("retry").right() }

        assertEquals(failure.left(), first)
        assertEquals(tokenData("retry").right(), second)
    }

    @Test
    @DisplayName("Exception during issuance releases waiters with server_error")
    fun exceptionDuringIssuanceReleasesWaiters() = runTest {
        val cache = RefreshTokenResponseCache(gracePeriodMillis = 60_000L, clock = { 0L })
        val gate = CompletableDeferred<Unit>()

        // 勝者の発行処理は例外で失敗する
        val winner = async {
            runCatching {
                cache.getOrIssue("token-a") {
                    gate.await()
                    throw IllegalStateException("boom")
                }
            }
        }
        // 敗者は勝者の結果を待機する
        val waiter = async {
            cache.getOrIssue("token-a") {
                // 勝者が先にエントリを登録しているため、この発行処理は実行されない
                tokenData("unexpected").right()
            }
        }
        // 勝者・敗者の両方をサスペンド地点まで進めてからゲートを開放する
        // （勝者がエントリ登録済み・敗者が待機中の状態を確実に作る）
        testScheduler.runCurrent()
        gate.complete(Unit)

        // 勝者には例外が伝播し、待機者はserver_errorで解放される
        assertTrue(winner.await().isFailure)
        val waiterResult = waiter.await()
        assertTrue(waiterResult is Either.Left)
        assertEquals(OAuthErrorCode.SERVER_ERROR, (waiterResult as Either.Left).value.errorCode)

        // エントリは削除済みのため、次のリクエストで再試行できる
        val retry = cache.getOrIssue("token-a") { tokenData("retry").right() }
        assertEquals(tokenData("retry").right(), retry)
    }

    @Test
    @DisplayName("Different tokenIds are issued independently")
    fun differentTokenIdsAreIssuedIndependently() = runTest {
        val cache = RefreshTokenResponseCache(gracePeriodMillis = 60_000L, clock = { 0L })

        val a = cache.getOrIssue("token-a") { tokenData("a").right() }
        val b = cache.getOrIssue("token-b") { tokenData("b").right() }

        // tokenIdごとに独立して発行される
        assertEquals(tokenData("a").right(), a)
        assertEquals(tokenData("b").right(), b)
    }
}
