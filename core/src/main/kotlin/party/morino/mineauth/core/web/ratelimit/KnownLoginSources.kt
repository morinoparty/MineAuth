package party.morino.mineauth.core.web.ratelimit

import kotlin.time.Duration

/**
 * ログインに成功した接続元とユーザー名の組を覚えておく
 *
 * ユーザー名ごとのバックオフは、以前にそのユーザー名でログインに成功した接続元には適用しない。
 * プレイヤー名は公開情報なので、全員に適用すると、ブロックが切れるたびに失敗するだけで本人を締め出し続けられる。
 * 成功の記録は正しいパスワードを知っている人にしか作れないので、攻撃者が新しい接続元を増やしても除外の対象にはならない。
 * 外部に依存しない純粋なクラスで、時刻は注入された関数から取得する
 *
 * @param retention 最後の成功から組を忘れるまでの時間
 * @param clock 現在時刻（ミリ秒）の取得関数（テストで時間を制御するために注入可能）
 * @param maxKeys 保持する組の上限。超えると最も長く使われていない組を忘れる
 */
class KnownLoginSources(
    retention: Duration,
    private val clock: () -> Long = System::currentTimeMillis,
    maxKeys: Int = DEFAULT_MAX_KEYS
) {
    private val retentionMillis = retention.inWholeMilliseconds

    // 組ごとの最後の成功時刻（ミリ秒）
    // キーはリクエスト由来なので上限付きのマップで保持する（このマップ自体をロックにも使う）
    private val lastSuccessAt = LruMap<Long>(maxKeys)

    /**
     * ログインの成功を記録する
     *
     * @param ip クライアントのキー
     * @param username リクエストで提示されたユーザー名
     */
    fun recordSuccess(ip: ClientIpKey, username: String): Unit = synchronized(lastSuccessAt) {
        lastSuccessAt[RateLimitKeys.loginSource(ip, username)] = clock()
    }

    /**
     * 接続元が保持期間内にそのユーザー名でログインに成功しているかを確認する
     * 期限切れの組はアクセス時に削除する（アクセスされないものはmaxKeysの上限で追い出される）
     *
     * @param ip クライアントのキー
     * @param username リクエストで提示されたユーザー名
     * @return 成功の記録がある場合はtrue
     */
    fun isKnown(ip: ClientIpKey, username: String): Boolean = synchronized(lastSuccessAt) {
        val key = RateLimitKeys.loginSource(ip, username)
        val succeededAt = lastSuccessAt[key] ?: return@synchronized false
        if (clock() - succeededAt < retentionMillis) return@synchronized true
        lastSuccessAt.remove(key)
        false
    }
}
