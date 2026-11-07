package party.morino.mineauth.core.web.router.auth

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.utils.KeyUtils
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import party.morino.mineauth.core.web.components.auth.OIDCDiscoveryResponse

/**
 * .well-known エンドポイント
 * OIDC Discovery と JWKs を提供
 *
 * 設定は /mineauth reload で置き換わるため、保持せずリクエストごとにKoinから取得する。
 * 古いissuerを公開し続けると、新しいissを持つトークンと食い違ってしまう（OIDC Discovery 1.0 Section 4.3）。
 */
object WellKnownRouter : KoinComponent {
    fun Route.wellKnownRouter() {
        route(".well-known") {
            // OIDC Discovery Endpoint
            // OpenID Connect Discovery 1.0 準拠
            get("openid-configuration") {
                // emailFormatが設定されている場合、emailスコープを有効化
                val emailEnabled = get<MineAuthConfig>().server.emailFormat != null
                // LuckPermsがインストールされている場合、rolesスコープを有効化
                val rolesEnabled = LuckPermsIntegration.available
                // トークンのissと同じ値をissuerとして使う（両者は一致している必要がある）
                val response = OIDCDiscoveryResponse.fromBaseUrl(
                    baseUrl = get<JWTConfigData>().issuer,
                    emailEnabled = emailEnabled,
                    rolesEnabled = rolesEnabled
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
