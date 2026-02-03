package party.morino.mineauth.addons.odailyquests

import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.GlobalContext
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

        // Koinコンテキストの確認
        if (!verifyKoin()) {
            logger.severe("Koin is not initialized. MineAuth must be loaded before this addon.")
            server.pluginManager.disablePlugin(this)
            return
        }

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
     * Koinコンテキストの検証
     * MineAuthが起動したKoinコンテキストが存在するか確認する
     *
     * @return Koinが初期化済みの場合はtrue
     */
    private fun verifyKoin(): Boolean {
        val koinApp = GlobalContext.getOrNull()
        return koinApp != null
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
