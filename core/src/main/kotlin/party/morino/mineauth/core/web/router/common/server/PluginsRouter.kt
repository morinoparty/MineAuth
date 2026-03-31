package party.morino.mineauth.core.web.router.common.server

import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object PluginsRouter : KoinComponent {
    private val pluginInfoService: PluginInfoService by inject()

    fun Route.pluginsRoutes() {
        get("/plugins") {
            // サービスからプラグイン情報を取得して返却
            call.respond(pluginInfoService.getInstalledPlugins())
        }
    }
}
