package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.velocity.*
import org.apache.commons.lang3.RandomStringUtils
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.file.data.OAuthConfigData
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.authorizedData
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.oauth.OAuthService
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.buildErrorRedirectUri
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.buildSuccessRedirectUri
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.validatePKCE

/**
 * OAuth2.0の認可エンドポイントを提供するルーター
 * 認可コードフローを実装しています
 */
object AuthorizeRouter: KoinComponent {
    private val oauthConfig: OAuthConfigData by inject()

    /**
     * 認可エンドポイントのルーティングを設定
     * GET /authorize: 認可画面を表示
     * POST /authorize: 認可リクエストを処理
     */
    fun Route.authorizeRouter() {
        // 認可画面を表示するエンドポイント
        get("/authorize") {
            // OAuth2.0の必須パラメータを取得
            val responseType = call.parameters["response_type"]
            val clientId = call.parameters["client_id"]
            val redirectUri = call.parameters["redirect_uri"]
            val scope = call.parameters["scope"]
            val state = call.parameters["state"]
            val codeChallenge = call.parameters["code_challenge"]
            val codeChallengeMethod = call.parameters["code_challenge_method"]
            // OIDC nonce: リプレイ攻撃防止用（任意パラメータ）
            val nonce = call.parameters["nonce"]

            // 必須パラメータのバリデーション
            if (clientId == null || redirectUri == null || scope == null || responseType != "code" || state == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid request")
                return@get
            }

            // PKCE(Proof Key for Code Exchange)のバリデーション
            if (!validatePKCE(codeChallenge, codeChallengeMethod)) {
                call.respond(HttpStatusCode.BadRequest, "This server only supports S256 code_challenge_method")
                return@get
            }

            // クライアントの存在確認とリダイレクトURIの検証
            val clientData = OAuthService.getClientData(clientId)
            if (clientData == null) {
                call.respond(HttpStatusCode.BadRequest, "Invalid client")
                return@get
            }

            if (!OAuthService.validateClientAndRedirectUri(clientData, redirectUri)) {
                call.respond(HttpStatusCode.BadRequest, "Invalid redirect_uri")
                return@get
            }

            // 認可画面に表示するデータの準備
            val model = mutableMapOf(
                "clientId" to clientData.clientId,
                "clientName" to clientData.clientName,
                "redirectUri" to redirectUri,
                "responseType" to "code",
                "state" to state,
                "scope" to scope,
                "codeChallenge" to codeChallenge,
                "codeChallengeMethod" to (codeChallengeMethod ?: "S256"),
                "logoUrl" to oauthConfig.logoUrl,
                "applicationName" to oauthConfig.applicationName,
            )
            // nonceが存在する場合はモデルに追加（OIDC対応）
            nonce?.let { model["nonce"] = it }

            // Velocityテンプレートを使用して認可画面を表示
            call.respond(VelocityContent("authorize.vm", model as Map<String, Any>))
        }

        // 認可リクエストを処理するエンドポイント
        post("/authorize") {
            // フォームパラメータの取得
            val formParameters = call.receiveParameters()
            val username = formParameters["username"]
            val password = formParameters["password"]
            val responseType = formParameters["response_type"]
            val clientId = formParameters["client_id"]
            val redirectUri = formParameters["redirect_uri"]
            val scope = formParameters["scope"]
            val state = formParameters["state"]
            val codeChallenge = formParameters["code_challenge"]
            val codeChallengeMethod = formParameters["code_challenge_method"] ?: "S256"
            // OIDC nonce: リプレイ攻撃防止用（任意パラメータ）
            val nonce = formParameters["nonce"]

            // パラメータのバリデーション
            if (username == null || password == null || responseType != "code" || clientId == null || redirectUri == null || scope == null || state == null || codeChallenge == null) {
                val errorUri = buildErrorRedirectUri(redirectUri!!, "invalid_request", "It does not have the required parameters", state!!)
                call.respondRedirect(errorUri)
                return@post
            }

            // ユーザー認証
            val authResult = OAuthService.authenticateUser(username, password)
            val uniqueId = when (authResult) {
                is AuthenticationResult.Success -> authResult.uniqueId
                is AuthenticationResult.Failed -> {
                    val errorDescription = when (authResult.reason) {
                        AuthenticationError.PLAYER_NOT_FOUND -> "This player has never played before"
                        AuthenticationError.PLAYER_NOT_REGISTERED -> "This player is not registered"
                        AuthenticationError.INVALID_PASSWORD -> "Password is incorrect"
                    }
                    val errorUri = buildErrorRedirectUri(redirectUri, "access_denied", errorDescription, state)
                    call.respondRedirect(errorUri)
                    return@post
                }
            }

            // 認可コードの生成と保存
            // 認証時刻を記録（ID Tokenのauth_timeクレームに使用）
            val authTime = System.currentTimeMillis()
            val code = RandomStringUtils.randomAlphanumeric(16)
            authorizedData[code] = AuthorizedData(
                clientId = clientId,
                redirectUri = redirectUri,
                scope = scope,
                state = state,
                codeChallenge = codeChallenge,
                codeChallengeMethod = codeChallengeMethod,
                uniqueId = uniqueId,
                nonce = nonce,
                authTime = authTime
            )

            // 認可コードをリダイレクトURIに付加してリダイレクト
            val successUri = buildSuccessRedirectUri(redirectUri, code, state)
            call.respondRedirect(successUri)
            return@post
        }
    }
}