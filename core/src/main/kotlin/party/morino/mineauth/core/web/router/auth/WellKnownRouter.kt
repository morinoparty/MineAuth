package party.morino.mineauth.core.web.router.auth

import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.utils.KeyUtils
import party.morino.mineauth.core.web.components.auth.OIDCDiscoveryResponse

/**
 * .well-known エンドポイント
 * OIDC Discovery と JWKs を提供
 */
object WellKnownRouter : KoinComponent {
    private val plugin: MineAuth by inject()
    private val config: MineAuthConfig by inject()

    fun Route.wellKnownRouter() {
        route(".well-known") {
            // OIDC Discovery Endpoint
            // OpenID Connect Discovery 1.0 準拠
            get("openid-configuration") {
                val response = OIDCDiscoveryResponse.fromBaseUrl(config.server.baseUrl)
                call.respond(response)
            }

            // JWKs Endpoint
            // generated ディレクトリから jwks.json を返す
            staticFiles("", KeyUtils.generatedDir) {
                default("jwks.json")
            }
        }
    }
}
