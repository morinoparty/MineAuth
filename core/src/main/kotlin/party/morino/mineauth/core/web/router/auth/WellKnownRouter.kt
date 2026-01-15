package party.morino.mineauth.core.web.router.auth

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.utils.KeyUtils
import party.morino.mineauth.core.web.components.auth.OIDCDiscoveryResponse

/**
 * .well-known エンドポイント
 * OIDC Discovery と JWKs を提供
 */
object WellKnownRouter : KoinComponent {
    private val config: MineAuthConfig by inject()

    fun Route.wellKnownRouter() {
        route(".well-known") {
            // OIDC Discovery Endpoint
            // OpenID Connect Discovery 1.0 準拠
            get("openid-configuration") {
                // emailFormatが設定されている場合、emailスコープを有効化
                val emailEnabled = config.server.emailFormat != null
                val response = OIDCDiscoveryResponse.fromBaseUrl(
                    baseUrl = config.server.baseUrl,
                    emailEnabled = emailEnabled
                )
                call.respond(response)
            }

            // JWKs Endpoint
            // RFC 7517 準拠の JWK Set を返す
            get("jwks.json") {
                val jwksFile = KeyUtils.generatedDir.resolve("jwks.json")
                if (jwksFile.exists()) {
                    call.respondText(jwksFile.readText(), ContentType.Application.Json)
                } else {
                    call.respond(HttpStatusCode.NotFound)
                }
            }
        }
    }
}
