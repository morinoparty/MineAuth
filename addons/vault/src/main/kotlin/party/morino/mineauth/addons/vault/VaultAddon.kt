package party.morino.mineauth.addons.vault

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.vault.config.VaultConfig
import party.morino.mineauth.addons.vault.routes.VaultHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * Vault連携アドオン
 * MineAuthのHTTP API経由でVaultの経済機能にアクセス可能にする
 */
class VaultAddon : JavaPlugin() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

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
     * 設定ファイルを読み込む
     * 存在しない場合はデフォルト値で作成する
     *
     * @return 読み込んだ設定
     */
    private fun loadConfig(): VaultConfig {
        val configFile = dataFolder.resolve("config.json")
        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            configFile.writeText(json.encodeToString(VaultConfig()))
        }
        return runCatching {
            json.decodeFromString<VaultConfig>(configFile.readText())
        }.getOrElse { e ->
            logger.warning("Failed to load config.json: ${e.message}. Using default config.")
            VaultConfig()
        }
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

        val config = loadConfig()

        startKoin {
            modules(
                module {
                    // Economyインスタンスをシングルトンとして登録
                    single<Economy> { economy }
                    // 設定をシングルトンとして登録
                    single { config }
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
