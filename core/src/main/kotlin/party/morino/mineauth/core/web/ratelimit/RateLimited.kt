package party.morino.mineauth.core.web.ratelimit

/**
 * 流量制限やバックオフでリクエストを止めたことを表す
 * 想定内の結果なので、例外ではなくEitherのLeftとして返す
 *
 * @property retryAfterSeconds 再試行できるまでの秒数（Retry-Afterヘッダーの値。1以上）
 */
data class RateLimited(val retryAfterSeconds: Long)

/**
 * 待ち時間をRetry-Afterの秒数に変換する
 * 切り捨てると解除前に再試行させてしまうので切り上げ、0秒も「すぐ再試行してよい」と読めるので最小を1秒にする
 *
 * @param waitMillis 待ち時間（ミリ秒）
 * @return 1以上の秒数
 */
internal fun retryAfterSecondsOf(waitMillis: Long): Long = ((waitMillis + 999) / 1000).coerceAtLeast(1)
