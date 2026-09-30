package party.morino.mineauth.core.web.router.auth

import io.ktor.server.routing.*
import party.morino.mineauth.core.web.router.auth.WellKnownRouter.wellKnownRouter
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.oauthRouter

object AuthRouter {
    fun Route.authRouter() {
        wellKnownRouter()
        oauthRouter()
    }
}