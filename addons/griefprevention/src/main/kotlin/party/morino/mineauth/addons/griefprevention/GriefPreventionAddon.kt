package party.morino.mineauth.addons.griefprevention

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import me.ryanhamshire.GriefPrevention.GriefPrevention
import me.ryanhamshire.GriefPrevention.DataStore
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.griefprevention.config.GriefPreventionConfig
import party.morino.mineauth.addons.griefprevention.routes.ClaimHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * GriefPrevention連携アドオン
 * MineAuthのHTTP API経由でGriefPreventionのクレーム情報にアクセス可能にする
 */
class GriefPreventionAddon : JavaPlugin() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

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
     * 設定ファイルを読み込む
     * 存在しない場合はデフォルト値で作成する
     *
     * @return 読み込んだ設定
     */
    private fun loadConfig(): GriefPreventionConfig {
        val configFile = dataFolder.resolve("config.json")
        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            configFile.writeText(json.encodeToString(GriefPreventionConfig()))
        }
        return runCatching {
            json.decodeFromString<GriefPreventionConfig>(configFile.readText())
        }.getOrElse { e ->
            logger.warning("Failed to load config.json: ${e.message}. Using default config.")
            GriefPreventionConfig()
        }
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

        val config = loadConfig()

        startKoin {
            modules(
                module {
                    // DataStoreをシングルトンとして登録
                    single<DataStore> { GriefPrevention.instance.dataStore }
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
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(ClaimHandler())
    }
}
