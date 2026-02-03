package party.morino.mineauth.core.web.router.plugin

import io.ktor.server.response.*
import io.ktor.server.routing.*
import party.morino.mineauth.core.integration.IntegrationInitializer

object PluginRouter {
    fun Route.pluginRouter() {
        get {
            call.respondText("Hello, plugin!")
        }
        get("/availableIntegrations") {
            call.respond(IntegrationInitializer.availableIntegrations.map { it.name })
        }
        // Vault, QuickShop-Hikariはアドオン方式に移行したため、
        // RegisterHandler API経由で登録される（vault-addon, quickshop-hikari-addon参照）
    }
}