package party.morino.mineauth.addons.quickshop

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.addons.quickshop.routes.ShopHandler
import party.morino.mineauth.api.MineAuthAPI

class QuickShopHikariAddon : JavaPlugin() {

    lateinit var mineAuthAPI: MineAuthAPI

    override fun onEnable() {
        logger.info("QuickShop Hikari Addon enabled")
        mineAuthAPI = server.servicesManager.getRegistration(MineAuthAPI::class.java)?.provider
            ?: throw IllegalStateException("MineAuthAPI not found")

        setupMineAuth()
    }

    override fun onDisable() {
        logger.info("QuickShop Hikari Addon disabled")
    }

    private fun setupMineAuth() {
        val handler = mineAuthAPI.createHandler(this)

        handler.register(
            ShopHandler()
        )
    }

}