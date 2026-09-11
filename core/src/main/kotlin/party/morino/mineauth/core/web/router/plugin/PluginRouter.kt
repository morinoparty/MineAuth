package party.morino.mineauth.core.web.router.plugin

import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.integration.IntegrationInitializer
import party.morino.mineauth.core.plugin.MineAuthApiImpl
import party.morino.mineauth.core.web.components.plugin.AvailableIntegrationsResponse
import party.morino.mineauth.core.web.components.plugin.RegisteredEndpointData
import party.morino.mineauth.core.web.components.plugin.RegisteredPluginData

/**
 * /api/v1/plugins 配下のコア提供エンドポイント
 * アドオンが登録するエンドポイントはここではなくPluginEndpointDispatcherが処理する
 */
object PluginRouter : KoinComponent {
    private val mineAuthApi: MineAuthApiImpl by inject()

    fun Route.pluginRouter() {
        get {
            call.respondText("Hello, plugin!")
        }
        // 利用可能な連携の一覧
        // コア内蔵連携（LuckPermsなど）に加えて、アドオンがregister()で登録した
        // 名前空間とエンドポイントも返し、どのAPIが使えるかを1回で確認できるようにする
        get("/availableIntegrations") {
            val integrations = IntegrationInitializer.availableIntegrations.map { it.name }
            val plugins = mineAuthApi.activeRegistrations().map { (namespace, registration) ->
                RegisteredPluginData(
                    namespace = namespace,
                    plugin = registration.plugin.name,
                    basePath = registration.basePath,
                    endpoints = registration.endpoints.map { RegisteredEndpointData.from(it) },
                )
            }
            call.respond(AvailableIntegrationsResponse(integrations = integrations, plugins = plugins))
        }
        // Vault, QuickShop-Hikariはアドオン方式に移行したため、
        // RegisterHandler API経由で登録される（vault-addon, quickshop-hikari-addon参照）
    }
}
