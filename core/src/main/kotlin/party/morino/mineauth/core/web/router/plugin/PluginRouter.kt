package party.morino.mineauth.core.web.router.plugin

import io.ktor.server.response.*
import io.ktor.server.routing.*
import party.morino.mineauth.core.integration.IntegrationInitializer
import party.morino.mineauth.core.integration.vault.VaultIntegration
import party.morino.mineauth.core.web.router.plugin.vault.VaultRouter.vaultRouter

object PluginRouter {
    fun Route.pluginRouter() {
        get {
            call.respondText("Hello, plugin!")
        }
        get("/availableIntegrations") {
            call.respond(IntegrationInitializer.availableIntegrations.map { it.name })
        }
        if (VaultIntegration.available) {
            vaultRouter()
        }
        // QuickShop-Hikariはアドオン方式に移行したため、
        // RegisterHandler API経由で登録される（quickshop-hikari-addon参照）
    }
}