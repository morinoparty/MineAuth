package party.morino.mineauth.addons.quickshop.stock

import kotlin.math.floor

/**
 * ショップの在庫（販売ショップ）または空き容量（買取ショップ）
 *
 * QuickShop-Hikari の `allShops` はチャンクが読み込まれていないショップも返すが、
 * そのショップの `remainingStock` / `remainingSpace` はインベントリを参照できず常に 0 になる。
 * そのため、数えられなかったショップは 0 ではなく [Unknown] として区別する。
 */
sealed interface ShopStock {
    /** アドミンショップなど、在庫・容量の制限が無い */
    data object Unlimited : ShopStock

    /**
     * 在庫または空き容量の数
     *
     * 買取ショップでは、チェストの空き容量とオーナーの所持金で買い取れる数の少ない方になる。
     *
     * @property amount ショップの取引単位（shopStackingAmount）での数
     */
    data class Counted(val amount: Int) : ShopStock

    /** ワールドが読み込まれていない、チャンクを読み込めなかった、1リクエストあたりの読み込み上限に達したなどで分からない */
    data object Unknown : ShopStock

    companion object {
        /**
         * 買取ショップが買い取れる数を求める
         * 空き容量と、オーナーの所持金で払える回数の少ない方になる。
         *
         * 所持金が分からない（Vault が無い・オーナーが不明）場合や、価格が 0 以下の場合は空き容量だけで決める。
         *
         * @param space 空き容量（取引単位での数）
         * @param price 1 取引単位あたりの買取価格
         * @param balance オーナーの所持金
         * @return 買い取れる数
         */
        fun buyable(space: Int, price: Double, balance: Double?): Counted {
            // 所持金で制限できない場合は空き容量のみ
            if (balance == null || price <= 0.0) return Counted(space)
            // 負の所持金は 0 として扱い、Int の範囲に収める
            val affordable = floor(balance.coerceAtLeast(0.0) / price).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
            return Counted(minOf(space, affordable))
        }
    }
}

/**
 * API の `remaining` の値にする
 * アドミンショップは従来どおり -1、数えられなかった場合は null を返す
 *
 * @return remaining の値
 */
fun ShopStock.toRemaining(): Int? =
    when (this) {
        ShopStock.Unlimited -> -1
        is ShopStock.Counted -> amount
        ShopStock.Unknown -> null
    }
