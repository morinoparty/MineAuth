package party.morino.mineauth.addons.quickshop

import com.ghostchu.quickshop.api.QuickShopAPI
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.addons.quickshop.routes.ShopHandler
import party.morino.mineauth.api.MineAuthAPI

/**
 * QuickShop-Hikari連携アドオン
 * MineAuthのHTTP API経由でQuickShopのショップ情報にアクセス可能にする
 */
class QuickShopHikariAddon : JavaPlugin() {

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

        // Koinモジュールの設定
        if (!setupKoin()) {
            logger.severe("Failed to setup Koin")
            server.pluginManager.disablePlugin(this)
            return
        }

        // MineAuthにハンドラーを登録
        setupMineAuth()

        logger.info("QuickShop Hikari Addon enabled")
    }

    override fun onDisable() {
        logger.info("QuickShop Hikari Addon disabled")
    }

    /**
     * Koinの設定
     * MineAuthが起動したKoinコンテキストにQuickShopAPIモジュールを追加する
     *
     * @return 初期化に成功した場合はtrue
     */
    private fun setupKoin(): Boolean {
        // MineAuth側のKoinが起動済みであることを確認
        val koinApp = GlobalContext.getOrNull()
        if (koinApp == null) {
            logger.severe("Koin is not initialized. MineAuth must be loaded before this addon.")
            return false
        }

        // QuickShop-Hikariプラグインの存在確認
        val qsPlugin = server.pluginManager.getPlugin("QuickShop-Hikari")
        if (qsPlugin == null) {
            logger.warning("QuickShop-Hikari plugin not found")
            return false
        }
        logger.info("QuickShop-Hikari plugin found: ${qsPlugin.pluginMeta.version}")

        val quickShopModule = module {
            // QuickShopAPIをシングルトンとして登録
            single<QuickShopAPI> { QuickShopAPI.getInstance() }
        }

        loadKoinModules(quickShopModule)
        return true
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
