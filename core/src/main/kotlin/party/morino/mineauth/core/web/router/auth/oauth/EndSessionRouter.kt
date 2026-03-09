package party.morino.mineauth.core.web.router.auth.oauth

import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.web.components.auth.ClientData

/**
 * OpenID Connect RP-Initiated Logout 1.0 エンドポイント
 *
 * クライアントがユーザーのログアウトを開始するためのエンドポイント
 * https://openid.net/specs/openid-connect-rpinitiated-1_0.html
 *
 * このOPはJWTベースのステートレス設計であり、サーバー側セッションを持たない。
 * ログアウトはクライアント側でトークンを破棄する責務となる。
 * サーバー側でのトークン失効が必要な場合は、先に /oauth2/revoke を呼び出すこと。
 */
object EndSessionRouter : KoinComponent {
    private val plugin: MineAuth by inject()

    /**
     * GET /oauth2/end_session エンドポイントを登録する
     *
     * RP-Initiated Logoutに基づくエンドセッションエンドポイント
     * - id_token_hint（推奨）: 以前発行されたID Tokenで、ユーザー/クライアントを特定
     * - post_logout_redirect_uri（オプション）: ログアウト後のリダイレクト先
     * - state（オプション）: リダイレクトに含めるstate値
     */
    fun Route.endSessionRouter() {
        get("/end_session") {
            val idTokenHint = call.request.queryParameters["id_token_hint"]
            val postLogoutRedirectUri = call.request.queryParameters["post_logout_redirect_uri"]
            val state = call.request.queryParameters["state"]

            // id_token_hintが提供された場合、検証してクライアントを特定
            var validatedClientId: String? = null
            if (idTokenHint != null) {
                validatedClientId = validateIdTokenHint(idTokenHint)
                if (validatedClientId == null) {
                    // ID Tokenが無効な場合、エラーレスポンス
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Invalid id_token_hint")
                    return@get
                }
            }

            // post_logout_redirect_uriが提供された場合、登録済みURIとの一致を検証
            if (postLogoutRedirectUri != null) {
                if (validatedClientId == null) {
                    // id_token_hintなしでredirect_uriを指定した場合はエラー
                    // クライアントを特定できないためリダイレクトURIの検証ができない
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "id_token_hint is required when post_logout_redirect_uri is specified")
                    return@get
                }

                // クライアントの登録済みリダイレクトURIと照合
                if (!validatePostLogoutRedirectUri(validatedClientId, postLogoutRedirectUri)) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Invalid post_logout_redirect_uri")
                    return@get
                }

                // post_logout_redirect_uriにリダイレクト（stateがあれば付与）
                val redirectUrl = buildPostLogoutRedirectUrl(postLogoutRedirectUri, state)
                call.respondRedirect(redirectUrl)
            } else {
                // リダイレクト先がない場合、ログアウト完了メッセージを返す
                call.respond(HttpStatusCode.OK, mapOf("message" to "Logout successful"))
            }
        }
    }

    /**
     * ID Token Hintを検証してクライアントIDを抽出する
     *
     * ID Tokenの署名を検証し（期限切れは許容）、audクレームからクライアントIDを取得する
     * OIDC RP-Initiated Logout仕様: ログアウト時点でID Tokenが期限切れである可能性があるため
     * lenientVerifierで期限切れを安全に許容し、署名検証は常に実行される
     *
     * @param idTokenHint 検証するID Token
     * @return クライアントID、検証失敗時はnull
     */
    private fun validateIdTokenHint(idTokenHint: String): String? {
        return try {
            // 署名は必ず検証しつつ、有効期限チェックを無効化する
            // JWT.decode()を使わないことで、署名未検証のJWTからクレームを抽出するリスクを排除
            val jwt = try {
                JwtProvider.lenientVerifier.verify(idTokenHint)
            } catch (e: JWTVerificationException) {
                // 署名不正・issuer不一致 → 無効
                return null
            }

            // audクレームからクライアントIDを取得
            jwt.audience?.firstOrNull()
        } catch (e: Exception) {
            plugin.logger.warning("ID token hint validation error: ${e.message}")
            null
        }
    }

    /**
     * post_logout_redirect_uriを登録済みクライアントのリダイレクトURIと照合する
     *
     * @param clientId クライアントID
     * @param postLogoutRedirectUri 検証するリダイレクトURI
     * @return 有効な場合true
     */
    private fun validatePostLogoutRedirectUri(clientId: String, postLogoutRedirectUri: String): Boolean {
        val clientData = try {
            ClientData.getClientData(clientId)
        } catch (e: Exception) {
            return false
        }

        // 既存のリダイレクトURI検証ロジックを再利用
        return OAuthValidation.validateRedirectUri(clientData, postLogoutRedirectUri)
    }

    /**
     * ログアウト後のリダイレクトURLを構築する
     *
     * @param postLogoutRedirectUri ベースリダイレクトURI
     * @param state リダイレクトに含めるstate値（オプション）
     * @return 構築されたリダイレクトURL
     */
    private fun buildPostLogoutRedirectUrl(postLogoutRedirectUri: String, state: String?): String {
        if (state == null) return postLogoutRedirectUri

        val builder = URLBuilder()
        builder.takeFrom(postLogoutRedirectUri)
        builder.parameters.append("state", state)
        return builder.buildString()
    }
}
