package party.morino.mineauth.addons.betonquest.data

import kotlinx.serialization.Serializable

/**
 * クエストパッケージの情報を表すデータクラス
 */
@Serializable
data class QuestPackageData(
    // パッケージ名（識別子）
    val name: String,
    // クエスト一覧
    val quests: List<QuestData>
)

/**
 * 個別クエストの情報を表すデータクラス
 */
@Serializable
data class QuestData(
    // クエストID
    val id: String,
    // クエスト名（表示名）
    val name: String?,
    // クエストの説明
    val description: List<String>?
)

/**
 * 利用可能なクエスト一覧のレスポンス
 */
@Serializable
data class AvailableQuestsResponse(
    // クエストパッケージ一覧
    val packages: List<QuestPackageData>,
    // 総クエスト数
    val totalQuests: Int
)
