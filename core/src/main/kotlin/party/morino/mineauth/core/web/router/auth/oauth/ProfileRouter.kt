package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import party.morino.mineauth.core.utils.PlayerUtils.toOfflinePlayer
import party.morino.mineauth.core.utils.PlayerUtils.toUUID
import party.morino.mineauth.core.web.components.auth.UserInfoResponse

/**
 * OpenID Connect UserInfo Endpoint
 * OIDC Core Section 5.3 準拠
 */
object ProfileRouter : KoinComponent {
    private val config: MineAuthConfig by inject()

    fun Route.profileRouter() {
        authenticate("user-oauth-token") {
            // OIDC UserInfo Endpoint
            // アクセストークンのスコープに基づいてクレームを返す
            get("/userinfo") {
                val principal = call.principal<JWTPrincipal>()!!
                val payload = principal.payload

                // JWTからプレイヤー情報を取得
                val playerUniqueId = payload.getClaim("playerUniqueId").asString().toUUID()
                val offlinePlayer = playerUniqueId.toOfflinePlayer()
                val username = offlinePlayer.name ?: "Unknown"

                // JWTからスコープを取得してリストに変換
                val scopeString = payload.getClaim("scope").asString() ?: ""
                val scopes = scopeString.split(" ").filter { it.isNotBlank() }

                // emailFormatが設定されている場合、メールアドレスを生成
                val email = config.server.emailFormat?.let { format ->
                    UserInfoResponse.generateEmail(
                        emailFormat = format,
                        uuid = playerUniqueId.toString(),
                        username = username
                    )
                }

                // rolesスコープがリクエストされている場合、LuckPermsからグループを取得
                val roles = if (scopes.contains("roles") && LuckPermsIntegration.available) {
                    LuckPermsIntegration.getPlayerGroups(playerUniqueId)
                } else {
                    null
                }

                // スコープに基づいてOIDC準拠のレスポンスを構築
                val response = UserInfoResponse.fromScopes(
                    sub = playerUniqueId.toString(),
                    username = username,
                    scopes = scopes,
                    email = email,
                    roles = roles
                )

                call.respond(response)
            }
        }
    }
}
