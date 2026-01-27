package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizeRouter.authorizeRouter
import party.morino.mineauth.core.web.router.auth.oauth.ProfileRouter.profileRouter
import party.morino.mineauth.core.web.router.auth.oauth.RevokeRouter.revokeRouter
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.tokenRouter

object OAuthRouter: KoinComponent {
    fun Route.oauthRouter() {
        route("/oauth2") {
            authorizeRouter()
            tokenRouter()
            profileRouter()
            revokeRouter()
        }
    }

    val authorizedData = hashMapOf<String, AuthorizedData>()
}