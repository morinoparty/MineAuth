package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.velocity.*
import java.security.SecureRandom
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
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
    private val plugin: MineAuth by inject()
    private val oauthConfig: OAuthConfigData by inject()
    private val config: MineAuthConfig by inject()
    // RFC 6749 Section 10.10: 認可コード生成に暗号学的に安全な乱数を使用
    private val secureRandom = SecureRandom()
    private const val CODE_LENGTH = 32
    private val CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

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

            // RFC 6749 Section 4.1.2.1: client_idまたはredirect_uriが不正/欠落の場合は400
            // これらが不正な場合はリダイレクトしてはならない（オープンリダイレクタ防止）
            if (clientId == null || redirectUri == null) {
                plugin.logger.warning("Authorize error: Missing client_id or redirect_uri")
                call.respond(HttpStatusCode.BadRequest, "Invalid request: missing client_id or redirect_uri")
                return@get
            }

            // クライアントの存在確認とリダイレクトURIの検証（リダイレクト前に必須）
            val clientData = OAuthService.getClientData(clientId)
            if (clientData == null) {
                plugin.logger.warning("Authorize error: Invalid client - client_id=$clientId")
                call.respond(HttpStatusCode.BadRequest, "Invalid client")
                return@get
            }

            if (!OAuthService.validateClientAndRedirectUri(clientData, redirectUri)) {
                plugin.logger.warning("Authorize error: Invalid redirect_uri - client_id=$clientId, redirect_uri=$redirectUri")
                call.respond(HttpStatusCode.BadRequest, "Invalid redirect_uri")
                return@get
            }

            // RFC 6749 Section 4.1.2.1: redirect_uriが有効な場合、その他のパラメータ不正はエラーリダイレクト
            if (scope == null || responseType != "code" || state == null) {
                plugin.logger.warning("Authorize error: Invalid request - missing required parameters")
                val errorState = state ?: ""
                val errorUri = buildErrorRedirectUri(redirectUri, "invalid_request", "Missing required parameters", errorState)
                call.respondRedirect(errorUri)
                return@get
            }

            // PKCE(Proof Key for Code Exchange)のバリデーション
            if (!validatePKCE(codeChallenge, codeChallengeMethod)) {
                plugin.logger.warning("Authorize error: Unsupported PKCE method - client_id=$clientId")
                val errorUri = buildErrorRedirectUri(redirectUri, "invalid_request", "This server only supports S256 code_challenge_method", state)
                call.respondRedirect(errorUri)
                return@get
            }

            // 認可画面に表示するデータの準備
            // スコープ文字列をリストに分割（テンプレートで個別表示するため）
            val scopeList = scope.split(" ").filter { it.isNotBlank() }

            val model = mutableMapOf(
                "clientId" to clientData.clientId,
                "clientName" to clientData.clientName,
                "redirectUri" to redirectUri,
                "responseType" to "code",
                "state" to state,
                "scope" to scope,
                "scopeList" to scopeList,
                "issuer" to config.server.baseUrl,
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
                // redirect_uriとstateが存在する場合のみエラーリダイレクト
                if (redirectUri != null && state != null) {
                    val errorUri = buildErrorRedirectUri(redirectUri, "invalid_request", "It does not have the required parameters", state)
                    call.respondRedirect(errorUri)
                } else {
                    call.respond(HttpStatusCode.BadRequest, "Invalid request: missing required parameters")
                }
                return@post
            }

            // RFC 6749 Section 4.1.3: クライアントの存在確認とリダイレクトURIの検証
            // POST時もGET時と同様にDB上の登録情報と照合する
            val clientData = OAuthService.getClientData(clientId)
            if (clientData == null) {
                plugin.logger.warning("Authorize POST error: Invalid client - client_id=$clientId")
                call.respond(HttpStatusCode.BadRequest, "Invalid client")
                return@post
            }
            if (!OAuthService.validateClientAndRedirectUri(clientData, redirectUri)) {
                plugin.logger.warning("Authorize POST error: Invalid redirect_uri - client_id=$clientId, redirect_uri=$redirectUri")
                call.respond(HttpStatusCode.BadRequest, "Invalid redirect_uri")
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
            // RFC 6749 Section 10.10: 暗号学的に安全な乱数で認可コードを生成
            val code = String(CharArray(CODE_LENGTH) { CODE_CHARS[secureRandom.nextInt(CODE_CHARS.size)] })
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