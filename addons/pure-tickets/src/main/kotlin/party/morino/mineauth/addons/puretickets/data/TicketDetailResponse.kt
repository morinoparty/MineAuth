package party.morino.mineauth.addons.puretickets.data

import kotlinx.serialization.Serializable

/**
 * チケットに対する操作の履歴
 *
 * @property action 操作種別（MESSAGE / NOTE / CLAIM / CLOSE / COMPLETE / ASSIGN / UNCLAIM / REOPEN）
 * @property time 操作日時（ISO 8601形式）
 * @property sender 操作者のUUID
 * @property message メッセージ内容（メッセージ系操作の場合のみ）
 */
@Serializable
data class InteractionResponse(
    val action: String,
    val time: String,
    val sender: String,
    val message: String?,
)

/**
 * チケット詳細のレスポンス（操作履歴を含む）
 *
 * @property id チケットID
 * @property status チケットの状態
 * @property message チケットのメッセージ
 * @property claimer 対応者のUUID
 * @property interactions 操作履歴のリスト
 */
@Serializable
data class TicketDetailResponse(
    val id: Int,
    val status: String,
    val message: String?,
    val claimer: String?,
    val interactions: List<InteractionResponse>,
)
