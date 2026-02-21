package party.morino.mineauth.addons.quickshop

import com.ghostchu.quickshop.api.QuickShopAPI
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.addons.quickshop.config.QuickShopConfig
import party.morino.mineauth.addons.quickshop.routes.ShopHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * QuickShop-Hikari連携アドオン
 * MineAuthのHTTP API経由でQuickShopのショップ情報にアクセス可能にする
 */
class QuickShopHikariAddon : JavaPlugin() {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    private lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("QuickShop Hikari Addon enabling...")

        // MineAuthAPIの取得（safe castを使用してgraceful disableに対応）
        val mineAuthPlugin = server.pluginManager.getPlugin("MineAuth")
        val api = mineAuthPlugin as? MineAuthAPI
        if (api == null) {
            logger.severe("MineAuth plugin not found or is not a valid MineAuthAPI instance")
            server.pluginManager.disablePlugin(this)
            return
        }
        mineAuthAPI = api

        // QuickShop-Hikariプラグインの存在確認
        if (!verifyQuickShop()) {
            logger.severe("QuickShop-Hikari plugin not found")
            server.pluginManager.disablePlugin(this)
            return
        }

        // Koinの初期化
        setupKoin()

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("QuickShop Hikari Addon enabled")
    }

    override fun onDisable() {
        stopKoin()
        logger.info("QuickShop Hikari Addon disabled")
    }

    /**
     * QuickShop-Hikariプラグインの検証
     *
     * @return QuickShop-Hikariがロードされている場合はtrue
     */
    private fun verifyQuickShop(): Boolean {
        val qsPlugin = server.pluginManager.getPlugin("QuickShop-Hikari")
        if (qsPlugin == null) {
            logger.warning("QuickShop-Hikari plugin not found")
            return false
        }
        logger.info("QuickShop-Hikari plugin found: ${qsPlugin.pluginMeta.version}")
        return true
    }

    /**
     * 設定ファイルを読み込む
     * 存在しない場合はデフォルト値で作成する
     *
     * @return 読み込んだ設定
     */
    private fun loadConfig(): QuickShopConfig {
        val configFile = dataFolder.resolve("config.json")
        if (!configFile.exists()) {
            configFile.parentFile.mkdirs()
            configFile.writeText(json.encodeToString(QuickShopConfig()))
        }
        return runCatching {
            json.decodeFromString<QuickShopConfig>(configFile.readText())
        }.getOrElse { e ->
            logger.warning("Failed to load config.json: ${e.message}. Using default config.")
            QuickShopConfig()
        }
    }

    /**
     * Koinの初期化
     * アドオン独自のKoinコンテキストを起動する
     */
    private fun setupKoin() {
        val config = loadConfig()

        startKoin {
            modules(
                module {
                    // QuickShopAPIをシングルトンとして登録
                    single<QuickShopAPI> { QuickShopAPI.getInstance() }
                    // 設定をシングルトンとして登録
                    single { config }
                }
            )
        }
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/quickshophikari/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(ShopHandler())
    }
}
