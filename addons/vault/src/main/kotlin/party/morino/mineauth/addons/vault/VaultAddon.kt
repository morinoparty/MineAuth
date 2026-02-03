package party.morino.mineauth.addons.vault

import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.vault.routes.VaultHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * Vault連携アドオン
 * MineAuthのHTTP API経由でVaultの経済機能にアクセス可能にする
 */
class VaultAddon : JavaPlugin() {

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("Vault Addon enabling...")

        // MineAuthAPIの取得（safe castを使用してgraceful disableに対応）
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

        // Koinの初期化
        if (!setupKoin()) {
            logger.severe("Failed to setup Koin - Economy provider not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("Vault Addon enabled")
    }

    override fun onDisable() {
        stopKoin()
        logger.info("Vault Addon disabled")
    }

    /**
     * Koinの初期化
     * アドオン独自のKoinコンテキストを起動する
     *
     * @return 初期化に成功した場合はtrue、Economy providerが見つからない場合はfalse
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
                    // Economyインスタンスをシングルトンとして登録
                    single<Economy> { economy }
                }
            )
        }
        return true
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/vault-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(VaultHandler())
    }
}
