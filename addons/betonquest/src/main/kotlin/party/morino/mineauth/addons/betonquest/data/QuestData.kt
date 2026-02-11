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
