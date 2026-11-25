package party.morino.mineauth.addons.quickshop.stock

import java.util.concurrent.ConcurrentHashMap

/**
 * ショップごとの在庫・空き容量の短期キャッシュ
 *
 * 一覧 API は 1 回で最大 maxLimit 件のショップを返すため、呼ばれるたびに数百のチャンクを読み込まないよう、
 * 数えた結果を [ttlMillis] の間だけ使い回す。
 * [ShopStock.Unknown] は保存しない（上限で数えられなかったショップが、次のリクエストで数えられるようにするため）。
 * HTTP のワーカースレッドとメインスレッドの両方から触るので、[ConcurrentHashMap] で保持する。
 *
 * @property ttlMillis 結果を使い回す時間（ミリ秒）。0 以下ならキャッシュしない
 * @property clock 現在時刻（ミリ秒）を返す関数。テストで差し替えられるようにする
 */
class ShopStockCache(
    private val ttlMillis: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * キャッシュの 1 件
     *
     * @property stock 数えた結果
     * @property expiresAt この時刻（ミリ秒）以降は使わない
     */
    private data class Entry(val stock: ShopStock, val expiresAt: Long)

    // shopId → 数えた結果
    private val entries = ConcurrentHashMap<Long, Entry>()

    /**
     * 有効期限内の結果を取得する
     *
     * @param shopId ショップID
     * @return 有効期限内の結果。無い・期限切れならnull
     */
    fun get(shopId: Long): ShopStock? {
        val entry = entries[shopId] ?: return null
        // 期限切れは返さずに捨てる（同時に新しい値が入っていた場合は消さない）
        if (clock() >= entry.expiresAt) {
            entries.remove(shopId, entry)
            return null
        }
        return entry.stock
    }

    /**
     * 数えた結果を保存する
     * [ShopStock.Unknown] とキャッシュ無効（TTL が 0 以下）の場合は何もしない
     *
     * @param shopId ショップID
     * @param stock 数えた結果
     */
    fun put(shopId: Long, stock: ShopStock) {
        if (ttlMillis <= 0 || stock == ShopStock.Unknown) return
        entries[shopId] = Entry(stock, clock() + ttlMillis)
    }

    /**
     * 指定したショップの結果を捨てる
     * 設定変更（販売・買取の切り替えなど）で数え方が変わったときに使う
     *
     * @param shopId ショップID
     */
    fun invalidate(shopId: Long) {
        entries.remove(shopId)
    }

    /**
     * 期限切れの結果をまとめて捨てる
     * 削除されたショップの結果が残り続けないよう、一覧の取得ごとに呼ぶ
     */
    fun purgeExpired() {
        val now = clock()
        // ラムダを標準ライブラリへ渡さないよう、イテレーターで消す（ConcurrentHashMap のイテレーターは並行更新に耐える）
        val iterator = entries.values.iterator()
        while (iterator.hasNext()) {
            if (now >= iterator.next().expiresAt) iterator.remove()
        }
    }
}
