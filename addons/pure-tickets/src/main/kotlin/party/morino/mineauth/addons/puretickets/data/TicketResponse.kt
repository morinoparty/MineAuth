package party.morino.mineauth.addons.puretickets.data

import kotlinx.serialization.Serializable

/**
 * チケット概要のレスポンス
 *
 * @property id チケットID
 * @property player チケット作成者のUUID
 * @property status チケットの状態（OPEN / CLAIMED / CLOSED）
 * @property message チケットのメッセージ
 * @property claimer 対応者のUUID（未対応の場合はnull）
 */
@Serializable
data class TicketResponse(
    val id: Int,
    val player: String,
    val status: String,
    val message: String?,
    val claimer: String?,
)

/**
 * チケット一覧のレスポンス
 *
 * @property tickets チケットのリスト
 * @property total チケットの総数
 */
@Serializable
data class TicketsResponse(
    val tickets: List<TicketResponse>,
    val total: Int,
)
