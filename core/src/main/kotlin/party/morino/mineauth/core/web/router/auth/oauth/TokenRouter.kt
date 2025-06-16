package party.morino.mineauth.core.web.router.auth.oauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.utils.KeyUtils.getKeys
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.components.auth.TokenData
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.authorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.validateCodeVerifier
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.*

object TokenRouter: KoinComponent {
    val plugin: MineAuth by inject()
    private const val EXPIRES_IN = 300
    fun Route.tokenRouter() {
        post("/token") {
            val formParameters = call.receiveParameters()
            val grantType = formParameters["grant_type"]
            if (grantType == "refresh_token") {
                //refresh_tokenの処理 https://tools.ietf.org/html/rfc6749#section-6
                val clientId = formParameters["client_id"]
                val clientSecret = formParameters["client_secret"]
                if (clientId == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid request")
                    return@post
                }

                if(clientSecret != null){
                    val refreshToken = formParameters["refresh_token"]
                    if (refreshToken == null) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid request")
                        return@post
                    }
                    val data = ClientData.getClientData(clientId) as ClientData.ConfidentialClientData
                    if (data.clientSecret != clientSecret) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid client_secret")
                        return@post
                    }
                    val (token , state) = issueTokenWithRefreshToken(refreshToken)
                    call.respond(HttpStatusCode.OK, TokenData(token, "Bearer", EXPIRES_IN, state, refreshToken))

                }else{
                    //PublicClientDataの場合
                    val refreshToken = formParameters["refresh_token"]
                    val redirectUri = formParameters["redirect_uri"]
                    if (refreshToken == null || redirectUri == null) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid request")
                        return@post
                    }
                    val data = ClientData.getClientData(clientId)
                    if (data.redirectUri != redirectUri) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid redirect_uri")
                        return@post
                    }
                    val (token , state) = issueTokenWithRefreshToken(refreshToken)
                    call.respond(HttpStatusCode.OK, TokenData(token, "Bearer", EXPIRES_IN, state, refreshToken))
                }
            }else if (grantType == "authorization_code") {
                //authorization_codeの処理 https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.3
                val code = formParameters["code"]
                val redirectUri = formParameters["redirect_uri"]
                val clientId = formParameters["client_id"]
                val codeVerifier = formParameters["code_verifier"]

                val clientSecret = formParameters["client_secret"] //client_secret_post
                val data = authorizedData[code] ?: run {
                    authorizedData.remove(code)
                    call.respond(HttpStatusCode.BadRequest, "Invalid code")
                    return@post
                }
                authorizedData.remove(code)

                if (code == null || redirectUri == null || clientId == null || codeVerifier == null) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid request")
                    return@post
                }
                if(clientSecret != null){
                    //ConfidentialClientDataの場合
                    val clientData = ClientData.getClientData(clientId) as ClientData.ConfidentialClientData
                    if (clientData.clientSecret != clientSecret) {
                        call.respond(HttpStatusCode.BadRequest, "Invalid client_secret")
                        return@post
                    }
                }

                if (data.clientId != clientId || data.redirectUri != redirectUri) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid client_id or redirect_uri")
                    return@post
                }

                if (!validateCodeVerifier(data.codeChallenge, codeVerifier)) {
                    call.respond(HttpStatusCode.BadRequest, "Invalid code_verifier")
                    return@post
                }

                val token = issueToken(data, clientId)
                val refreshToken = issueRefreshToken(data, clientId)

                call.respond(
                    HttpStatusCode.OK, TokenData(
                        token, "Bearer", EXPIRES_IN, data.state, refreshToken
                    )
                )
            }else{
                call.respond(HttpStatusCode.BadRequest, "Invalid grant_type")
            }
        }
    }

    private fun issueToken(
        data: AuthorizedData, clientId: String?
    ): String = JWT.create().withIssuer(get<JWTConfigData>().issuer)
        .withAudience(data.clientId)
        .withNotBefore(Date(System.currentTimeMillis()))
        .withExpiresAt(Date(System.currentTimeMillis() + EXPIRES_IN * 1_000.toLong()))
        .withIssuedAt(Date(System.currentTimeMillis()))
        .withJWTId(UUID.randomUUID().toString())
        .withClaim("client_id", clientId).withClaim("playerUniqueId", data.uniqueId.toString()).withClaim("scope", data.scope).withClaim("state", data.state).withClaim("scope", data.scope)
        .withClaim("token_type", "token").sign(
            Algorithm.RSA256(
                getKeys().second as RSAPublicKey, getKeys().first as RSAPrivateKey
            )
        )

    private fun issueRefreshToken(
        data: AuthorizedData, clientId: String?
    ): String = JWT.create().withIssuer(get<JWTConfigData>().issuer)
        .withAudience(data.clientId)
        .withNotBefore(Date(System.currentTimeMillis()))
        .withExpiresAt(Date(System.currentTimeMillis() + (3_600_000.toLong() * 24 * 30)))
        .withIssuedAt(Date(System.currentTimeMillis())).withJWTId(UUID.randomUUID().toString())
        .withClaim("client_id", clientId)
        .withClaim("playerUniqueId", data.uniqueId.toString())
        .withClaim("scope", data.scope)
        .withClaim("state", data.state)
        .withClaim("scope", data.scope)
        .withClaim("token_type", "refresh_token").sign(
            Algorithm.RSA256(
                getKeys().second as RSAPublicKey, getKeys().first as RSAPrivateKey
            )
        )

    private fun issueTokenWithRefreshToken(refreshToken: String) : Pair<String, String> {
        val jwt = JWT.decode(refreshToken)
        val clientId = jwt.getClaim("client_id").asString()
        val playerUniqueId = jwt.getClaim("playerUniqueId").asString()
        val scope = jwt.getClaim("scope").asString()
        val state = jwt.getClaim("state").asString()
        val token = issueToken(AuthorizedData(clientId, "", scope, state, "", UUID.fromString(playerUniqueId)), clientId)
        return Pair(token, state)
    }
}