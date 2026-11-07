package party.morino.mineauth.core.web.ratelimit

import kotlin.time.Duration

/**
 * キーごとの連続失敗に対する指数バックオフ
 *
 * [freeFailures]回までの失敗は素通りさせ、それを超えると失敗のたびに待ち時間を倍にする
 * （baseDelay * 2^(失敗回数 - freeFailures - 1)、上限はmaxDelay）。
 * ブロック中の試行は評価しない前提なので、呼び出し側は失敗として記録しない。
 * 外部に依存しない純粋なクラスで、時刻は注入された関数から取得する
 *
 * @param freeFailures ブロックせずに許す失敗の回数
 * @param baseDelay 最初のブロック時間
 * @param maxDelay ブロック時間の上限
 * @param idleExpiry 最後の失敗からこの時間が過ぎたキーを忘れる（maxDelay以上）
 * @param clock 現在時刻（ミリ秒）の取得関数（テストで時間を制御するために注入可能）
 * @param maxKeys 保持するキーの上限。超えると最も長く使われていないキーを忘れる
 */
class FailureBackoff(
    private val freeFailures: Int,
    baseDelay: Duration,
    maxDelay: Duration,
    idleExpiry: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
    maxKeys: Int = DEFAULT_MAX_KEYS
) {
    init {
        // ブロックの途中でキーを忘れると、ブロックが上限より早く解けてしまう
        require(idleExpiry >= maxDelay) { "idleExpiry must not be shorter than maxDelay" }
    }

    /**
     * キーごとの失敗の記録
     * ロックの中でのみ読み書きするので、フィールドはvarのままでよい
     */
    private class Entry(var failures: Int, var lastFailureAt: Long, var blockedUntil: Long)

    private val baseDelayMillis = baseDelay.inWholeMilliseconds
    private val maxDelayMillis = maxDelay.inWholeMilliseconds
    private val idleExpiryMillis = idleExpiry.inWholeMilliseconds

    // キーはリクエスト由来なので上限付きのマップで保持する（このマップ自体をロックにも使う）
    private val entries = LruMap<Entry>(maxKeys)

    /**
     * 次の試行を評価してよいかを確認する
     * ロックはマップの更新にだけ使うので、呼び出し側のI/Oやパスワード検証を巻き込まない
     *
     * @param key 数える単位のキー
     * @return 評価してよい場合はnull、ブロック中の場合は解除までの秒数（1以上）
     */
    fun retryAfterSeconds(key: String): Long? = synchronized(entries) {
        val now = clock()
        val remainingMillis = (liveEntry(key, now) ?: return@synchronized null).blockedUntil - now
        if (remainingMillis > 0) retryAfterSecondsOf(remainingMillis) else null
    }

    /**
     * 失敗を記録し、猶予を超えていればブロックを開始する
     *
     * @param key 数える単位のキー
     */
    fun recordFailure(key: String): Unit = synchronized(entries) {
        val now = clock()
        val entry = liveEntry(key, now) ?: Entry(0, now, now).also { entries[key] = it }
        entry.failures += 1
        entry.lastFailureAt = now
        if (entry.failures > freeFailures) {
            entry.blockedUntil = now + delayMillis(entry.failures)
        }
    }

    /**
     * 成功したキーを忘れる
     *
     * @param key 数える単位のキー
     */
    fun recordSuccess(key: String): Unit = synchronized(entries) {
        entries.remove(key)
    }

    /**
     * 有効なエントリを取得する
     * 放置されたエントリはアクセス時に削除する（アクセスされないものはmaxKeysの上限で追い出される）
     *
     * @param key 数える単位のキー
     * @param now 現在時刻（ミリ秒）
     * @return 有効なエントリ。無いか期限切れの場合はnull
     */
    private fun liveEntry(key: String, now: Long): Entry? {
        val entry = entries[key] ?: return null
        if (now - entry.lastFailureAt < idleExpiryMillis) return entry
        entries.remove(key)
        return null
    }

    /**
     * 失敗回数に応じたブロック時間を計算する
     * Long.MAX_VALUEを超える乗算やシフトは負の値になり、ブロックが無効になってしまうので事前に上限へ丸める
     *
     * @param failures 猶予を超えた失敗回数を含む総失敗回数
     * @return ブロック時間（ミリ秒）
     */
    private fun delayMillis(failures: Int): Long {
        val exponent = failures - freeFailures - 1
        if (exponent >= Long.SIZE_BITS - 1) return maxDelayMillis

        val multiplier = 1L shl exponent
        if (baseDelayMillis > maxDelayMillis / multiplier) return maxDelayMillis
        return baseDelayMillis * multiplier
    }
}
