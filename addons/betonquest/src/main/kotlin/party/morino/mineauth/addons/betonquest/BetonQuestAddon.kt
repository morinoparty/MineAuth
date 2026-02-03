package party.morino.mineauth.addons.betonquest

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.addons.betonquest.routes.QuestsHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * BetonQuest連携アドオン
 * MineAuthのHTTP API経由でBetonQuestのクエスト情報にアクセス可能にする
 */
class BetonQuestAddon : JavaPlugin() {

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("BetonQuest Addon enabling...")

        // MineAuthAPIの取得（safe castを使用してgraceful disableに対応）
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

        // BetonQuestプラグインの存在確認
        if (!verifyBetonQuest()) {
            logger.severe("BetonQuest plugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

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
     * 登録されたハンドラーは /api/v1/plugins/betonquest-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(QuestsHandler(this))
    }
}
