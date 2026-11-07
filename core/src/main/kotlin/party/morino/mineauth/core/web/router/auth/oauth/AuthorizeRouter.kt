package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import io.ktor.http.*
import io.ktor.server.application.install
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.opentelemetry.api.trace.Span
import java.security.SecureRandom
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.OAuthConfigData
import party.morino.mineauth.core.web.page.AuthorizePageRenderer
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import party.morino.mineauth.core.web.ratelimit.clientIpOf
import party.morino.mineauth.core.web.ratelimit.markRateLimited
import party.morino.mineauth.core.web.ratelimit.markVerifierBusy
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.data.AuthorizePageModel
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizationRequestValidator.isLoginSubmission
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizationRequestValidator.nonEmptyValue
import party.morino.mineauth.core.web.router.auth.oauth.AuthorizationRequestValidator.validateAuthorizationRequest
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.authorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.buildErrorRedirectUri
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.buildSuccessRedirectUri
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.web.telemetry.withSpan

/**
 * OAuth2.0の認可エンドポイントを提供するルーター
 * 認可コードフローを実装しています
 */
object AuthorizeRouter : KoinComponent {
    // RFC 6749 Section 10.10: 認可コード生成に暗号学的に安全な乱数を使用
    private val secureRandom = SecureRandom()
    private const val CODE_LENGTH = 32
    private val CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray()

    /**
     * 認可エンドポイントのルーティングを設定
     * GET /authorize: 認可リクエストを検証して認可画面を表示
     * POST /authorize: フォーム形式の認可リクエスト、またはログインの送信を処理
     */
    fun Route.authorizeRouter() {
        route("/authorize") {
            // RFC 9700 Section 4.16: GET・POST・エラー応答の全てをクリックジャッキングから守る
            install(AuthorizeSecurityHeaders)

            get {
                handleAuthorizationRequest(call.parameters)
            }

            post {
                val formParameters = call.receiveParameters()
                // OIDC Core 1.0 Section 3.1.2.1: POSTの認可リクエストにも対応する
                // 同梱のフロントエンドやカスタムテンプレートのログインフォームも同じURLへ送信するので、認証情報の有無で区別する
                if (isLoginSubmission(formParameters)) {
                    handleLoginSubmission(formParameters)
                } else {
                    handleAuthorizationRequest(formParameters)
                }
            }
        }
    }

    /**
     * 認可リクエストを検証して認可画面を表示する
     *
     * @param params クエリまたはフォームのパラメータ
     */
    private suspend fun RoutingContext.handleAuthorizationRequest(params: Parameters) {
        recordRequestAttributes(params)
        val request = validateAuthorizationRequest(params).getOrElse { error ->
            respondAuthorizeError(error, params)
            return
        }

        // 設定は/mineauth reloadで置き換わるため、保持せずリクエストごとに取得する
        val oauthConfig = get<OAuthConfigData>()
        val model = AuthorizePageModel(
            clientId = request.clientId,
            clientName = request.clientName,
            redirectUri = request.redirectUri,
            responseType = "code",
            state = request.state,
            // 認可されるスコープだけを利用者に見せる（フォームに戻ってきた値は送信時に再度解決する）
            scope = request.scope,
            scopeList = request.grantedScopes.map { it.value },
            // Discoveryやトークンのissと同じ、正規化済みのissuerを表示する
            issuer = get<JWTConfigData>().issuer,
            codeChallenge = request.codeChallenge,
            codeChallengeMethod = request.codeChallengeMethod,
            logoUrl = oauthConfig.logoUrl,
            applicationName = oauthConfig.applicationName,
            // nonceが存在する場合のみJSONに含まれる（OIDC対応）
            nonce = request.nonce,
        )

        // ビューモデルを埋め込んだReactの認可画面を返す
        call.respondText(AuthorizePageRenderer.render(model), ContentType.Text.Html)
    }

    /**
     * ログインの送信を処理し、認可コードを発行する
     * フォームの値は改ざんできるので、認可リクエストと同じ検証をサーバー側でやり直してから認証する
     *
     * @param params フォームのパラメータ
     */
    private suspend fun RoutingContext.handleLoginSubmission(params: Parameters) {
        recordRequestAttributes(params)
        val request = validateAuthorizationRequest(params).getOrElse { error ->
            respondAuthorizeError(error, params)
            return
        }

        val username = params.nonEmptyValue("username")
        val password = params.nonEmptyValue("password")
        if (username == null || password == null) {
            get<MineAuth>().logger.warning("Authorize error: Login submission without credentials")
            call.respondRedirect(
                buildErrorRedirectUri(
                    request.redirectUri,
                    OAuthErrorCode.INVALID_REQUEST.code,
                    "Missing required parameters: username and password",
                    request.state,
                )
            )
            return
        }

        // パスワードの総当たりを止める。検証を通った送信だけを数え、Argon2を実行する前に確認する
        // 設定は/mineauth reloadで置き換わるため、保持せずリクエストごとに取得する
        val rateLimits = get<OAuthRateLimits>()
        val clientIp = rateLimits.clientIpOf(call)
        rateLimits.acquireLoginAttempt(clientIp, username).onLeft { limited ->
            call.markRateLimited(limited)
            // RFC 6749 Section 4.1.2.1: この状態は検証済みのredirect_uriへのリダイレクトで通知する
            // ブラウザはRetry-Afterをクライアントへ渡さないので、待ち時間は説明文にも含める
            call.respondRedirect(
                buildErrorRedirectUri(
                    request.redirectUri,
                    OAuthErrorCode.TEMPORARILY_UNAVAILABLE.code,
                    "Too many login attempts. Retry after ${limited.retryAfterSeconds} seconds",
                    request.state,
                )
            )
            return
        }

        // ユーザー認証（結果をスパンとして記録する。ユーザー名・パスワードは記録しない）
        // 接続元のキーでArgon2Gateの枠を割り当て、1つの接続元からの大量のログインが他の接続元の検証を待たせないようにする
        val authResult = withSpan("oauth.user.authenticate") { span ->
            OAuthService.authenticateUser(username, password, clientIp).also { result ->
                val resultLabel = when (result) {
                    is AuthenticationResult.Success -> "success"
                    is AuthenticationResult.Failed -> result.reason.name.lowercase()
                    AuthenticationResult.VerifierBusy -> "verifier_busy"
                }
                span.setAttribute(TelemetryAttributes.AUTH_RESULT, resultLabel)
            }
        }
        val uniqueId = when (authResult) {
            is AuthenticationResult.Success -> {
                // この接続元を、このユーザー名のブロックから除外する接続元として覚える
                rateLimits.recordLoginSuccess(clientIp, username)
                authResult.uniqueId
            }
            // パスワードを評価していないので、ログインの失敗として記録しない
            AuthenticationResult.VerifierBusy -> {
                call.markVerifierBusy()
                // RFC 6749 Section 4.1.2.1: 一時的に処理できないことを検証済みのredirect_uriへ通知する
                call.respondRedirect(
                    buildErrorRedirectUri(
                        request.redirectUri,
                        OAuthErrorCode.TEMPORARILY_UNAVAILABLE.code,
                        "The server is busy. Retry later",
                        request.state,
                    )
                )
                return
            }
            is AuthenticationResult.Failed -> {
                rateLimits.recordLoginFailure(clientIp, username)
                val errorDescription = when (authResult.reason) {
                    AuthenticationError.PLAYER_NOT_FOUND -> "This player has never played before"
                    AuthenticationError.PLAYER_NOT_REGISTERED -> "This player is not registered"
                    AuthenticationError.INVALID_PASSWORD -> "Password is incorrect"
                }
                call.respondRedirect(buildErrorRedirectUri(request.redirectUri, "access_denied", errorDescription, request.state))
                return
            }
        }

        // テレメトリ: 認証成功したプレイヤーUUIDを記録する
        Span.current().setAttribute(TelemetryAttributes.PLAYER_UUID, uniqueId.toString())

        // 認可コードの生成と保存
        // 認証時刻を記録（ID Tokenのauth_timeクレームに使用）
        val authTime = System.currentTimeMillis()
        // RFC 6749 Section 10.10: 暗号学的に安全な乱数で認可コードを生成
        val code = String(CharArray(CODE_LENGTH) { CODE_CHARS[secureRandom.nextInt(CODE_CHARS.size)] })
        authorizedData[code] = AuthorizedData(
            clientId = request.clientId,
            redirectUri = request.redirectUri,
            // RFC 6749 Section 5.1: 要求と異なる場合があるので、付与したスコープを保存してトークンレスポンスで返す
            scope = request.scope,
            state = request.state,
            codeChallenge = request.codeChallenge,
            codeChallengeMethod = request.codeChallengeMethod,
            uniqueId = uniqueId,
            nonce = request.nonce,
            authTime = authTime,
            // この認可から発行される全トークンを束ねるID（リプレイや失効の際にまとめて失効させる単位）
            grantId = java.util.UUID.randomUUID().toString(),
        )

        // 認可コードをリダイレクトURIに付加してリダイレクト
        call.respondRedirect(buildSuccessRedirectUri(request.redirectUri, code, request.state))
    }

    /**
     * テレメトリ: サーバースパンにクライアントID・スコープを記録する（ユーザー名・パスワードは記録しない）
     *
     * @param params リクエストのパラメータ
     */
    private fun recordRequestAttributes(params: Parameters) {
        params["client_id"]?.let { Span.current().setAttribute(TelemetryAttributes.CLIENT_ID, it) }
        params["scope"]?.let { Span.current().setAttribute(TelemetryAttributes.OAUTH_SCOPE, it) }
    }

    /**
     * 認可リクエストの検証エラーを応答する
     * ShowErrorはリダイレクトせずに400で返し、RedirectWithErrorは検証済みのredirect_uriへ通知する
     *
     * @param error 検証エラー
     * @param params ログに残すリクエストのパラメータ
     */
    private suspend fun RoutingContext.respondAuthorizeError(error: AuthorizeError, params: Parameters) {
        val logger = get<MineAuth>().logger
        // リクエスト由来の値に改行などを含めてログを偽装されないよう、無害化してから出力する
        val clientId = sanitizeErrorDescription(params["client_id"])
        when (error) {
            is AuthorizeError.ShowError -> {
                val redirectUri = sanitizeErrorDescription(params["redirect_uri"])
                logger.warning("Authorize error: ${error.message} - client_id=$clientId, redirect_uri=$redirectUri")
                call.respond(HttpStatusCode.BadRequest, error.message)
            }
            is AuthorizeError.RedirectWithError -> {
                val description = sanitizeErrorDescription(error.description)
                logger.warning("Authorize error: ${error.code.code} - $description - client_id=$clientId")
                call.respondRedirect(buildErrorRedirectUri(error.redirectUri, error.code.code, error.description, error.state))
            }
        }
    }
}
