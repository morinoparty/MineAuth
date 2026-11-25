package party.morino.mineauth.addons.quickshop.paging

/**
 * カーソルベースページネーションの 1 ページ
 *
 * @property items このページの要素
 * @property nextCursor 次のページを取得するためのカーソル（このページ最後の ID）。最後のページならnull
 * @property hasMore 次のページがあるか
 */
data class ShopPage<T>(
    val items: List<T>,
    val nextCursor: Long?,
    val hasMore: Boolean,
) {
    companion object {
        /**
         * ID の昇順に並べ、[cursor] より後ろの要素を最大 [limit] 件取り出す（排他的カーソル）
         *
         * 返した nextCursor をそのまま次の cursor に渡すと、取りこぼし・重複なく全件を辿れる。
         *
         * inline にして、ラムダをオブジェクトとして標準ライブラリへ渡さないようにしている。
         * 以前の実装は `Sequence.filter { it.shopId > cursor }` を使っており、本番サーバーでは cursor を指定した
         * リクエストだけがこの段階で 500 になっていた（原因の例外は未確認。ローカルでは再現しない）。その構成を避けている。
         *
         * @param elements 全要素（順不同）
         * @param idOf 要素の ID（一意であること）
         * @param cursor この ID より後ろを返す。null なら先頭から
         * @param limit 1 ページの最大件数（1 以上）
         * @return 1 ページ分の結果
         */
        inline fun <T> of(elements: Collection<T>, crossinline idOf: (T) -> Long, cursor: Long?, limit: Int): ShopPage<T> {
            require(limit > 0) { "limit must be greater than 0" }
            // ID の昇順に並べ、カーソルより後ろだけを残す
            val sorted = elements.sortedBy { idOf(it) }
            val afterCursor = if (cursor == null) sorted else sorted.filter { idOf(it) > cursor }
            // limit+1 件目があれば次のページがある
            val hasMore = afterCursor.size > limit
            val items = afterCursor.take(limit)
            // 次のカーソルはこのページ最後の ID
            val nextCursor = if (hasMore) idOf(items.last()) else null
            return ShopPage(items, nextCursor, hasMore)
        }
    }
}
