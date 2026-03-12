package party.morino.mineauth.addons.votingplugin

import com.bencodez.votingplugin.VotingPluginHooks
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.votingplugin.routes.VotingPluginHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * VotingPlugin連携アドオン
 * MineAuthのHTTP API経由でVotingPluginの投票データにアクセス可能にする
 */
class VotingPluginAddon : JavaPlugin() {

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("VotingPlugin Addon enabling...")

        // MineAuthAPIの取得
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

        // VotingPluginの存在確認とKoinの初期化
        if (!setupKoin()) {
            logger.severe("Failed to setup Koin - VotingPlugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("VotingPlugin Addon enabled")
    }

    override fun onDisable() {
        stopKoin()
        logger.info("VotingPlugin Addon disabled")
    }

    /**
     * Koinの初期化
     * VotingPluginHooksインスタンスをDIコンテナに登録する
     *
     * @return 初期化に成功した場合はtrue
     */
    private fun setupKoin(): Boolean {
        // VotingPluginの存在確認
        val votingPlugin = server.pluginManager.getPlugin("VotingPlugin")
        if (votingPlugin == null) {
            logger.warning("VotingPlugin not found")
            return false
        }

        val hooks = VotingPluginHooks.getInstance()
        if (hooks == null) {
            logger.warning("VotingPluginHooks instance not available")
            return false
        }

        @Suppress("DEPRECATION")
        logger.info("VotingPlugin found: ${votingPlugin.description.version}")

        startKoin {
            modules(
                module {
                    // VotingPluginHooksをシングルトンとして登録
                    single<VotingPluginHooks> { hooks }
                }
            )
        }
        return true
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/voting-plugin-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(VotingPluginHandler())
    }
}
