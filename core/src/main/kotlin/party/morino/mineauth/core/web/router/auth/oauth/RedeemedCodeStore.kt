package party.morino.mineauth.core.web.router.auth.oauth

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 使用済みの認可コードを本来の有効期限まで覚えておくストア
 *
 * RFC 6749 Section 4.1.2: 認可コードの再使用を未知のコードと区別し、グラントを失効させるために使う。
 * 認可コードと同じくサーバーごとのメモリ上に保持するので、再起動すると記録は失われる
 *
 * @param clock 現在時刻の取得関数（テストで時間を制御するために注入可能）
 */
class RedeemedCodeStore(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private companion object {
        // クリーンアップの最小間隔（60秒）: OAuthRouter.cleanupExpiredCodesと同じくO(n)走査の頻度を抑える
        const val CLEANUP_INTERVAL_MS = 60_000L
    }

    // 認可コード → 使用済みの記録のマップ
    private val redeemedCodes = ConcurrentHashMap<String, RedeemedCode>()
    private val lastCleanupTime = AtomicLong(0)

    // 保持している記録の件数（有効期限切れで未削除のものを含む）
    val size: Int
        get() = redeemedCodes.size

    /**
     * 認可コードを使用済みとして記録する
     *
     * @param code 認可コード
     * @param redeemed 使用済みの記録
     */
    fun record(code: String, redeemed: RedeemedCode) {
        redeemedCodes[code] = redeemed
    }

    /**
     * 有効期限内の記録を探す
     * トークンエンドポイントの判定と同じく、有効期限ちょうどまでを有効とする
     *
     * @param code 認可コード
     * @return 有効期限内の記録。記録がない、または有効期限を過ぎている場合はnull
     */
    fun find(code: String): RedeemedCode? =
        redeemedCodes[code]?.takeIf { clock() <= it.expiresAt }

    /**
     * 有効期限を過ぎた記録を削除する（スロットリング付き）
     * リクエスト契機で呼ばれるため、最小間隔を設けて実行頻度を制限する
     */
    fun cleanupExpired() {
        val now = clock()
        val lastRun = lastCleanupTime.get()
        // 最小間隔以内の場合はスキップ（CASで競合を防止）
        if (now - lastRun < CLEANUP_INTERVAL_MS) return
        if (!lastCleanupTime.compareAndSet(lastRun, now)) return

        redeemedCodes.entries.removeIf { (_, redeemed) -> now > redeemed.expiresAt }
    }
}
