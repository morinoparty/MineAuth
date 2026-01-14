package party.morino.mineauth.addons.quickshop

import com.ghostchu.quickshop.api.QuickShopAPI
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
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

        // MineAuthAPIの取得
        mineAuthAPI = server.pluginManager.getPlugin("MineAuth") as MineAuthAPI?
            ?: throw IllegalStateException("MineAuth plugin not found")

        // Koinモジュールの設定
        setupKoin()

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
     */
    private fun setupKoin() {
        val quickShopModule = module {
            // QuickShopAPIのシングルトン登録
            single<QuickShopAPI> { QuickShopAPI.getInstance() }
        }

        // MineAuth側のKoinが起動済みならモジュールを追加する
        val koinApp = GlobalContext.getOrNull()
        if (koinApp != null) {
            loadKoinModules(quickShopModule)
            return
        }

        // Koin未起動の場合はアドオン側で起動する
        startKoin {
            modules(quickShopModule)
        }
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/quickshop-hikari-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        mineAuthAPI.createHandler(this)
            .register(ShopHandler())
    }
}
