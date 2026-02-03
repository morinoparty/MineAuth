package party.morino.mineauth.addons.betonquest.routes

import com.github.shynixn.mccoroutine.bukkit.minecraftDispatcher
import kotlinx.coroutines.withContext
import org.betonquest.betonquest.BetonQuest
import org.bukkit.plugin.Plugin
import party.morino.mineauth.addons.betonquest.data.AvailableQuestsResponse
import party.morino.mineauth.addons.betonquest.data.QuestData
import party.morino.mineauth.addons.betonquest.data.QuestPackageData
import party.morino.mineauth.api.annotations.GetMapping

/**
 * BetonQuestのクエスト情報を提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 *
 * @property plugin プラグインインスタンス（メインスレッド実行用）
 */
class QuestsHandler(private val plugin: Plugin) {

    /**
     * 利用可能なクエスト一覧を取得する
     * GET /quests
     *
     * @return 利用可能なクエストパッケージとクエストの一覧
     */
    @GetMapping("/quests")
    suspend fun getAvailableQuests(): AvailableQuestsResponse {
        // Bukkit APIはメインスレッドで実行する必要がある
        return withContext(plugin.minecraftDispatcher) {
            // BetonQuest APIから直接パッケージマネージャーを取得
            val questPackageManager = BetonQuest.getInstance().questPackageManager

            val packages = mutableListOf<QuestPackageData>()
            var totalQuests = 0

            // 全てのクエストパッケージを取得
            for ((packageName, questPackage) in questPackageManager.packages) {
                val quests = mutableListOf<QuestData>()

                // パッケージの設定を取得
                val config = questPackage.config

                // パッケージ内のクエスト（objectives）を取得
                val objectives = config.getConfigurationSection("objectives")
                objectives?.getKeys(false)?.forEach { objectiveId ->
                    // labelがない場合はobjectiveIdをフォールバックとして使用
                    val label = objectives.getString("$objectiveId.label") ?: objectiveId
                    quests.add(
                        QuestData(
                            id = objectiveId,
                            name = label,
                            description = null
                        )
                    )
                }

                // パッケージ内のjournal entries（クエストログ）を取得
                val journal = config.getConfigurationSection("journal")
                journal?.getKeys(false)?.forEach { journalId ->
                    val text = journal.getString(journalId)
                    if (text != null && !quests.any { it.id == journalId }) {
                        quests.add(
                            QuestData(
                                id = journalId,
                                name = journalId,
                                description = listOf(text)
                            )
                        )
                    }
                }

                totalQuests += quests.size
                packages.add(
                    QuestPackageData(
                        name = packageName,
                        quests = quests
                    )
                )
            }

            AvailableQuestsResponse(
                packages = packages,
                totalQuests = totalQuests
            )
        }
    }
}
