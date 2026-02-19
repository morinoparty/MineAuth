package party.morino.mineauth.addons.quickshop.data

import kotlinx.serialization.Serializable

/**
 * カーソルベースページネーション付きショップ一覧レスポンス
 *
 * cursorにはshopIdを使用し、排他的カーソル方式で動作する。
 * 「Web API: The Good Parts」のページネーション設計に準拠。
 */
@Serializable
data class PaginatedShopsResponse(
    // ショップデータのリスト
    val shops: List<ShopData>,
    // 次ページのカーソル（shopId）。最後のページの場合null
    val nextCursor: String?,
    // 次のページが存在するかどうか
    val hasMore: Boolean,
)
