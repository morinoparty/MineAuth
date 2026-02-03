package party.morino.mineauth.addons.odailyquests.routes

import com.ordwen.odailyquests.api.ODailyQuestsAPI
import org.bukkit.OfflinePlayer
import party.morino.mineauth.addons.odailyquests.data.PlayerQuestsResponse
import party.morino.mineauth.addons.odailyquests.data.QuestProgressData
import party.morino.mineauth.api.annotations.AuthedAccessUser
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus

/**
 * O'DailyQuestsのクエスト情報を提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class QuestsHandler {

    /**
     * 自分のクエスト情報を取得する
     * GET /quests/me
     *
     * @param player 認証済みプレイヤー
     * @return プレイヤーのクエスト進捗情報
     */
    @GetMapping("/quests/me")
    suspend fun getMyQuests(@AuthedAccessUser player: OfflinePlayer): PlayerQuestsResponse {
        // プレイヤー名を取得（オフラインプレイヤーでも名前は取得可能）
        val playerName = player.name
            ?: throw HttpError(HttpStatus.BAD_REQUEST, "Player name not found")

        // ODailyQuestsAPIからプレイヤーのクエスト情報を取得
        val playerQuests = ODailyQuestsAPI.getPlayerQuests(playerName)
            ?: throw HttpError(HttpStatus.NOT_FOUND, "Player quests not found. Player may not have logged in yet.")

        // クエスト情報をレスポンス形式に変換
        val questDataList = playerQuests.quests.map { (quest, progression) ->
            QuestProgressData(
                name = quest.questName,
                description = quest.questDesc,
                category = quest.categoryName,
                type = quest.questType,
                advancement = progression.advancement,
                requiredAmount = progression.requiredAmount,
                isAchieved = progression.isAchieved
            )
        }

        return PlayerQuestsResponse(
            quests = questDataList,
            achievedQuests = playerQuests.achievedQuests,
            totalAchievedQuests = playerQuests.totalAchievedQuests
        )
    }
}
