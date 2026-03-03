package party.morino.mineauth.addons.betonquest.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.betonquest.betonquest.BetonQuest
import org.betonquest.betonquest.api.profile.Profile
import org.betonquest.betonquest.database.PlayerData
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import party.morino.mineauth.addons.betonquest.data.JournalEntryData
import party.morino.mineauth.addons.betonquest.data.PlayerQuestDataResponse
import party.morino.mineauth.addons.betonquest.utils.coroutines.minecraft
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.TargetPlayer

/**
 * BetonQuestのクエスト情報を提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class QuestsHandler {

    /**
     * プレイヤーのクエストデータを取得する
     * GET /quests/{player}
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return プレイヤーのクエストデータ（タグ、ポイント、ジャーナル、オブジェクティブ、デイリークエスト）
     */
    @GetMapping("/quests/{player}")
    suspend fun getMyQuests(@TargetPlayer player: OfflinePlayer): PlayerQuestDataResponse {
        // Bukkit APIはメインスレッドで実行する必要がある
        // MCCoroutineではなく独自のディスパッチャーを使用してクラスローダー問題を回避
        return withContext(Dispatchers.minecraft) {
            val betonQuest = BetonQuest.getInstance()

            // UUIDからProfileを取得
            val profile = betonQuest.profileProvider.getProfile(player.uniqueId)

            // PlayerDataを取得（タグ・ポイント・ジャーナル用）
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
            // オンラインプレイヤーはObjectiveインスタンスからライブデータを取得し、
            // オフラインプレイヤーはDBスナップショットを使用する
            val objectives = buildObjectivesMap(betonQuest, profile, player, playerData)

            PlayerQuestDataResponse(
                tags = tags,
                points = points,
                journal = journal,
                objectives = objectives,
            )
        }
    }

    /**
     * オブジェクティブデータのマップを構築する
     * オンラインプレイヤーの場合はObjectiveインスタンスからリアルタイムデータを取得し、
     * オフラインプレイヤーの場合はDBスナップショット（rawObjectives）を使用する
     *
     * rawObjectivesはログイン時にDBからロードされた初期データであり、
     * プレイ中のObjective進捗変更はObjectiveインスタンスの
     * ObjectiveDataに保持されるため、ライブデータの取得が必要
     *
     * @param betonQuest BetonQuestインスタンス
     * @param profile プレイヤーのProfile
     * @param player 対象プレイヤー
     * @param playerData フォールバック用のPlayerData
     * @return オブジェクティブID -> シリアライズデータのマップ
     */
    private fun buildObjectivesMap(
        betonQuest: BetonQuest,
        profile: Profile,
        player: OfflinePlayer,
        playerData: PlayerData
    ): Map<String, String> {
        return try {
            val isOnline = Bukkit.getPlayer(player.uniqueId) != null
            if (isOnline) {
                // オンライン: Objectiveインスタンスから最新データを取得
                val activeObjectives = betonQuest.questTypeApi.getPlayerObjectives(profile)
                activeObjectives.associate { objective ->
                    objective.label.orEmpty() to objective.getData(profile).orEmpty()
                }
            } else {
                // オフライン: DBスナップショットを使用
                rawObjectivesToMap(playerData)
            }
        } catch (e: Exception) {
            // フォールバック: rawObjectivesを使用
            try {
                rawObjectivesToMap(playerData)
            } catch (_: Exception) {
                emptyMap()
            }
        }
    }

    /**
     * PlayerDataのrawObjectivesをKotlin型安全なMapに変換する
     */
    private fun rawObjectivesToMap(playerData: PlayerData): Map<String, String> {
        return playerData.rawObjectives
            .mapKeys { it.key.orEmpty() }
            .mapValues { it.value?.toString().orEmpty() }
    }
}
