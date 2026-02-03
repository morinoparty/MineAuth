package party.morino.mineauth.addons.odailyquests.data

import kotlinx.serialization.Serializable

/**
 * 個別クエストの進捗状況を表すデータクラス
 */
@Serializable
data class QuestProgressData(
    // クエスト名
    val name: String,
    // クエストの説明
    val description: List<String>,
    // クエストのカテゴリ
    val category: String,
    // クエストのタイプ
    val type: String,
    // 現在の進捗
    val advancement: Int,
    // 達成に必要な数
    val requiredAmount: Int,
    // 達成済みかどうか
    val isAchieved: Boolean
)

/**
 * プレイヤーのクエスト情報全体を表すレスポンス
 */
@Serializable
data class PlayerQuestsResponse(
    // クエスト一覧
    val quests: List<QuestProgressData>,
    // 達成済みクエスト数
    val achievedQuests: Int,
    // 総達成クエスト数（累計）
    val totalAchievedQuests: Int
)
