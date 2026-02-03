package party.morino.mineauth.addons.odailyquests

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.addons.odailyquests.routes.QuestsHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * O'DailyQuests連携アドオン
 * MineAuthのHTTP API経由でO'DailyQuestsのクエスト情報にアクセス可能にする
 */
class ODailyQuestsAddon : JavaPlugin() {

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("ODailyQuests Addon enabling...")

        // MineAuthAPIの取得（safe castを使用してgraceful disableに対応）
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

        // ODailyQuestsプラグインの存在確認
        if (!verifyODailyQuests()) {
            logger.severe("ODailyQuests plugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("ODailyQuests Addon enabled")
    }

    override fun onDisable() {
        logger.info("ODailyQuests Addon disabled")
    }

    /**
     * ODailyQuestsプラグインの検証
     *
     * @return ODailyQuestsがロードされている場合はtrue
     */
    private fun verifyODailyQuests(): Boolean {
        val odqPlugin = server.pluginManager.getPlugin("ODailyQuests")
        if (odqPlugin == null) {
            logger.warning("ODailyQuests plugin not found")
            return false
        }
        logger.info("ODailyQuests plugin found: ${odqPlugin.pluginMeta.version}")
        return true
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/odailyquests-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(QuestsHandler())
    }
}
