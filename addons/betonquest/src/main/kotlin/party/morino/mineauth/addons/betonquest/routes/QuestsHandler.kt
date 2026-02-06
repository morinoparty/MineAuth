package party.morino.mineauth.addons.betonquest.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.betonquest.betonquest.BetonQuest
import org.bukkit.OfflinePlayer
import party.morino.mineauth.addons.betonquest.data.JournalEntryData
import party.morino.mineauth.addons.betonquest.data.PlayerQuestDataResponse
import party.morino.mineauth.addons.betonquest.utils.coroutines.minecraft
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.GetMapping

/**
 * BetonQuestのクエスト情報を提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class QuestsHandler {

    /**
     * 認証済みプレイヤーのクエストデータを取得する
     * GET /quests/me
     *
     * @param player 認証済みプレイヤー（JWTから自動解決）
     * @return プレイヤーのクエストデータ（タグ、ポイント、ジャーナル、オブジェクティブ）
     */
    @GetMapping("/quests/me")
    suspend fun getMyQuests(@Authenticated player: OfflinePlayer): PlayerQuestDataResponse {
        // Bukkit APIはメインスレッドで実行する必要がある
        // MCCoroutineではなく独自のディスパッチャーを使用してクラスローダー問題を回避
        return withContext(Dispatchers.minecraft) {
            val betonQuest = BetonQuest.getInstance()

            // UUIDからProfileを取得
            val profile = betonQuest.profileProvider.getProfile(player.uniqueId)

            // PlayerDataを取得（オフラインプレイヤーにも対応）
            val playerData = betonQuest.playerDataStorage.getOffline(profile)

            // タグ一覧を取得
            val tags = playerData.tags.toList()

            // ポイント一覧を取得（カテゴリ名 -> ポイント数）
            val points = playerData.points.associate { point ->
                point.category to point.count
            }

            // ジャーナルエントリを取得
            val journal = playerData.entries.map { pointer ->
                JournalEntryData(
                    pointer = pointer.pointer().toString(),
                    timestamp = pointer.timestamp()
                )
            }

            // 進行中のオブジェクティブを取得
            // rawObjectivesがMap<String, String>を返すことを確認
            val objectives = try {
                playerData.rawObjectives.mapValues { it.value.toString() }
            } catch (e: Exception) {
                emptyMap()
            }

            PlayerQuestDataResponse(
                tags = tags,
                points = points,
                journal = journal,
                objectives = objectives
            )
        }
    }
}
