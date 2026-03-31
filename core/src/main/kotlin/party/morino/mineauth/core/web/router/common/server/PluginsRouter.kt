package party.morino.mineauth.core.web.router.common.server

import io.ktor.server.auth.authenticate
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.web.JwtCompleteCode

object PluginsRouter : KoinComponent {
    private val pluginInfoService: PluginInfoService by inject()

    fun Route.pluginsRoutes() {
        // サービスアカウントトークンによる認証を要求
        authenticate(JwtCompleteCode.SERVICE_TOKEN.code) {
            get("/plugins") {
                call.respond(pluginInfoService.getInstalledPlugins())
            }
        }
    }
}
