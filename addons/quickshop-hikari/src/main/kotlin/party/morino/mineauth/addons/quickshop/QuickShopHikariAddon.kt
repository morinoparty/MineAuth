package party.morino.mineauth.addons.quickshop

import com.ghostchu.quickshop.api.QuickShopAPI
import org.bukkit.plugin.java.JavaPlugin
import org.koin.core.context.GlobalContext
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
        mineAuthAPI = server.servicesManager.getRegistration(MineAuthAPI::class.java)?.provider
            ?: throw IllegalStateException("MineAuthAPI not found. Is MineAuth installed?")

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
     * QuickShopAPIをシングルトンとして登録する
     */
    private fun setupKoin() {
        val quickShopModule = module {
            // QuickShopAPIのシングルトン登録
            single<QuickShopAPI> { QuickShopAPI.getInstance() }
        }

        // 既存のKoinコンテキストにモジュールを追加
        GlobalContext.getOrNull()?.loadModules(listOf(quickShopModule))
            ?: throw IllegalStateException("Koin is not initialized. Is MineAuth loaded?")
    }

    /**
     * MineAuthにハンドラーを登録する
     * 登録されたハンドラーは /api/v1/plugins/quickshop-hikari-addon/ 配下で利用可能
     */
    private fun setupMineAuth() {
        val handler = mineAuthAPI.createHandler(this)

        handler.register(
            ShopHandler()
        )

        logger.info("Registered ShopHandler endpoints:")
        logger.info("  - GET /shops/{shopId}")
        logger.info("  - GET /users/{uuid}/shops")
        logger.info("  - GET /users/me/shops (auth required)")
        logger.info("  - GET /shops/{shopId}/setting (auth required, owner only)")
        logger.info("  - POST /shops/{shopId}/setting (auth required, owner only)")
    }
}
