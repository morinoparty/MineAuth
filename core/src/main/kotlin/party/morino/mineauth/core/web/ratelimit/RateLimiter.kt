package party.morino.mineauth.core.web.ratelimit

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlin.math.min

/**
 * キーごとのトークンバケットによる流量制限
 *
 * バケットは満杯で始まり、1分間に[requestsPerMinute]個のペースで連続的に回復する。
 * 外部に依存しない純粋なクラスで、時刻は注入された関数から取得する
 *
 * トークン1個をMILLIS_PER_MINUTEクレジットとして整数で数える。1ミリ秒ごとにrequestsPerMinuteクレジット回復するので、
 * 浮動小数点の誤差で「案内した時刻ちょうどの再試行」が拒否されることがない
 *
 * @param requestsPerMinute 1分あたりの許可回数（一度に許可できる回数の上限も兼ねる）。1以上
 * @param clock 現在時刻（ミリ秒）の取得関数（テストで時間を制御するために注入可能）
 * @param maxKeys 保持するキーの上限。超えると最も長く使われていないキーを忘れる
 */
class RateLimiter(
    private val requestsPerMinute: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    maxKeys: Int = DEFAULT_MAX_KEYS
) {
    init {
        require(requestsPerMinute > 0) { "requestsPerMinute must be positive: $requestsPerMinute" }
    }

    /**
     * キーごとの残りクレジット
     * ロックの中でのみ読み書きするので、フィールドはvarのままでよい
     */
    private class Bucket(var credits: Long, var refilledAt: Long)

    // キーはリクエスト由来なので上限付きのマップで保持する（このマップ自体をロックにも使う）
    private val buckets = LruMap<Bucket>(maxKeys)

    // 満杯のバケットのクレジット
    private val capacityCredits = requestsPerMinute * MILLIS_PER_MINUTE

    /**
     * トークンを1個取得する
     * ロックはマップの更新にだけ使うので、呼び出し側のI/Oやパスワード検証を巻き込まない
     *
     * @param key 数える単位のキー
     * @return 許可された場合はRight、制限中の場合は再試行までの秒数を持つLeft
     */
    fun tryAcquire(key: String): Either<RateLimited, Unit> = synchronized(buckets) {
        val now = clock()
        val bucket = buckets.getOrPut(key) { Bucket(capacityCredits, now) }
        refill(bucket, now)

        if (bucket.credits >= MILLIS_PER_MINUTE) {
            bucket.credits -= MILLIS_PER_MINUTE
            Unit.right()
        } else {
            // 不足分が回復するまでの時間を切り上げで求める
            val missingCredits = MILLIS_PER_MINUTE - bucket.credits
            val waitMillis = (missingCredits + requestsPerMinute - 1) / requestsPerMinute
            RateLimited(retryAfterSecondsOf(waitMillis)).left()
        }
    }

    /**
     * 前回からの経過時間に応じてクレジットを回復する
     *
     * @param bucket 対象のバケット
     * @param now 現在時刻（ミリ秒）
     */
    private fun refill(bucket: Bucket, now: Long) {
        // 1分で必ず満杯になるので、乗算があふれないよう経過時間を1分で頭打ちにする
        // システム時刻が巻き戻った場合は回復させない
        val elapsedMillis = (now - bucket.refilledAt).coerceIn(0, MILLIS_PER_MINUTE)
        bucket.credits = min(capacityCredits, bucket.credits + elapsedMillis * requestsPerMinute)
        bucket.refilledAt = now
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
