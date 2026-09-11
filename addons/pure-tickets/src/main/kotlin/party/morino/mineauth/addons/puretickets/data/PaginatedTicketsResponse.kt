package party.morino.mineauth.addons.puretickets.data

import kotlinx.serialization.Serializable

/**
 * カーソルベースページネーション付きチケット一覧レスポンス
 *
 * cursorにはチケットIDを使用し、排他的カーソル方式で動作する
 * （QuickShopアドオンの[PaginatedShopsResponse]と同じ設計）。
 *
 * @property tickets このページに含まれるチケットのリスト
 * @property total フィルタ条件に一致したチケットの総数（ページングに関係なく全件）
 * @property nextCursor 次ページのカーソル（チケットID）。最後のページの場合はnull
 * @property hasMore 次のページが存在するかどうか
 */
@Serializable
data class PaginatedTicketsResponse(
    val tickets: List<TicketResponse>,
    val total: Int,
    val nextCursor: String?,
    val hasMore: Boolean,
)
