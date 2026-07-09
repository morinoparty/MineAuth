package party.morino.mineauth.addons.betonquest

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.addons.betonquest.routes.QuestsHandler
import party.morino.mineauth.api.EndpointRegistrationException
import party.morino.mineauth.api.MineAuthApi

/**
 * BetonQuest連携アドオン
 * MineAuthのHTTP API経由でBetonQuestのクエスト情報にアクセス可能にする
 */
class BetonQuestAddon : JavaPlugin() {

    private lateinit var mineAuthApi: MineAuthApi

    override fun onEnable() {
        logger.info("BetonQuest Addon enabling...")

        // MineAuthApiの取得（ServicesManager経由。未ロードの場合はgraceful disableに対応）
        val api = MineAuthApi.get(server)
        if (api == null) {
            logger.severe("MineAuth plugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthApi = api

        // BetonQuestプラグインの存在確認
        if (!verifyBetonQuest()) {
            logger.severe("BetonQuest plugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        if (!setupMineAuth()) {
            server.pluginManager.disablePlugin(this)
            return
        }

        logger.info("BetonQuest Addon enabled")
    }

    override fun onDisable() {
        logger.info("BetonQuest Addon disabled")
    }

    /**
     * BetonQuestプラグインの検証
     * プラグインが有効かどうかのみ確認する
     * QuestPackageManagerはAPI呼び出し時に遅延ロードされる
     *
     * @return BetonQuestが正しくロードされている場合はtrue
     */
    private fun verifyBetonQuest(): Boolean {
        val bqPlugin = server.pluginManager.getPlugin("BetonQuest")
        if (bqPlugin == null || !bqPlugin.isEnabled) {
            logger.warning("BetonQuest plugin not found or not enabled")
            return false
        }
        logger.info("BetonQuest plugin found: ${bqPlugin.pluginMeta.version}")

        // QuestPackageManagerの確認は遅延ロードにより、API呼び出し時に行われる
        // ここではプラグインの存在確認のみ行う
        return true
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/betonquest 配下で利用可能
     *
     * @return 登録に成功した場合はtrue
     */
    private fun setupMineAuth(): Boolean {
        return try {
            val registration = mineAuthApi.register(this, "betonquest", QuestsHandler())
            logger.info("Mounted ${registration.endpoints.size} endpoints under ${registration.basePath}")
            true
        } catch (e: EndpointRegistrationException) {
            // 検証エラーの全リストを含むメッセージをログ出力する
            logger.severe(e.message)
            false
        }
    }
}
