package party.morino.mineauth.addons.griefprevention

import me.ryanhamshire.GriefPrevention.GriefPrevention
import me.ryanhamshire.GriefPrevention.DataStore
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.griefprevention.routes.ClaimHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * GriefPrevention連携アドオン
 * MineAuthのHTTP API経由でGriefPreventionのクレーム情報にアクセス可能にする
 */
class GriefPreventionAddon : JavaPlugin() {

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("GriefPrevention Addon enabling...")

        // MineAuthAPIの取得
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

        // GriefPreventionプラグインの検証
        if (!verifyGriefPrevention()) {
            logger.severe("GriefPrevention plugin not found or not enabled")
            server.pluginManager.disablePlugin(this)
            return
        }

        // Koinの初期化
        if (!setupKoin()) {
            logger.severe("Failed to setup Koin - required providers not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("GriefPrevention Addon enabled")
    }

    override fun onDisable() {
        stopKoin()
        logger.info("GriefPrevention Addon disabled")
    }

    /**
     * GriefPreventionプラグインの存在を検証する
     */
    private fun verifyGriefPrevention(): Boolean {
        val gpPlugin = server.pluginManager.getPlugin("GriefPrevention")
        if (gpPlugin == null || !gpPlugin.isEnabled) {
            logger.warning("GriefPrevention plugin not found or not enabled")
            return false
        }
        logger.info("GriefPrevention plugin found: ${gpPlugin.pluginMeta.version}")
        return true
    }

    /**
     * Koinの初期化
     * GriefPreventionのDataStoreとVaultのEconomyをシングルトンとして登録する
     *
     * @return 初期化に成功した場合はtrue
     */
    private fun setupKoin(): Boolean {
        // VaultのEconomy providerを取得
        val rsp = Bukkit.getServicesManager().getRegistration(Economy::class.java)
        if (rsp == null) {
            logger.warning("No economy provider found. Please install an economy plugin (e.g., EssentialsX)")
            return false
        }

        val economy = rsp.provider
        logger.info("Economy provider found: ${economy.name}")

        startKoin {
            modules(
                module {
                    // DataStoreをシングルトンとして登録
                    single<DataStore> { GriefPrevention.instance.dataStore }
                    // Economyインスタンスをシングルトンとして登録
                    single<Economy> { economy }
                }
            )
        }
        return true
    }

    /**
     * MineAuthにハンドラーを登録する
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(ClaimHandler())
    }
}
