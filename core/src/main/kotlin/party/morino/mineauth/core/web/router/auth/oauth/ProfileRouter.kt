package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import party.morino.mineauth.core.utils.PlayerUtils.toOfflinePlayer
import party.morino.mineauth.core.utils.PlayerUtils.toUUID
import party.morino.mineauth.core.web.components.auth.UserInfoResponse

/**
 * OpenID Connect UserInfo Endpoint
 * OIDC Core Section 5.3 準拠
 */
object ProfileRouter {
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

                // スコープに基づいてOIDC準拠のレスポンスを構築
                val response = UserInfoResponse.fromScopes(
                    sub = playerUniqueId.toString(),
                    username = username,
                    scopes = scopes
                )

                call.respond(response)
            }
        }
    }
}
