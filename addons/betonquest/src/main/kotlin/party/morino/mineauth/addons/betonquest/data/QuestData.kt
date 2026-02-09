package party.morino.mineauth.addons.betonquest.data

import kotlinx.serialization.Serializable

/**
 * プレイヤーのクエストデータレスポンス
 * GET /quests/me で返却される
 */
@Serializable
data class PlayerQuestDataResponse(
    // プレイヤーが持っているタグ一覧
    val tags: List<String>,
    // ポイントカテゴリとポイント数のマップ
    val points: Map<String, Int>,
    // ジャーナルエントリ一覧
    val journal: List<JournalEntryData>,
    // 進行中のオブジェクティブ（ID -> データ）
    val objectives: Map<String, String>,
    // デイリークエスト情報一覧
    val dailyQuests: List<DailyQuestInfo>
)

/**
 * ジャーナルエントリのデータクラス
 */
@Serializable
data class JournalEntryData(
    // エントリのポインタ（パッケージ.エントリ名）
    val pointer: String,
    // タイムスタンプ（エポックミリ秒）
    val timestamp: Long
)

/**
 * デイリークエスト情報のデータクラス
 * InternalManager、State、DisplayStorageの各variable objectiveから抽出される
 */
@Serializable
data class DailyQuestInfo(
    // クエストID（例: "crafting_arrow"）
    val questId: String,
    // 完全なパッケージ名（例: "NotEnoughQuests-DailyQuest-Quests-Easy-crafting_arrow"）
    val questPackage: String,
    // 難易度: "easy", "normal", "hard", "event"
    val difficulty: String,
    // クエストタイトル（DisplayStorageから取得）
    val title: String,
    // クエストの説明（DisplayStorageから取得）
    val description: String,
    // タスク完了フラグ（目標達成済みかどうか）
    val taskCompleted: Boolean,
    // クエスト完了フラグ（報酬受取済みかどうか）
    val questCompleted: Boolean,
    // 進捗率（0-100）
    val progressPercentage: Int
)
