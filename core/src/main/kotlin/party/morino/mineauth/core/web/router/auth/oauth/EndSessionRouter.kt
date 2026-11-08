package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.opentelemetry.api.trace.Span
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes

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
object EndSessionRouter {

    /**
     * GET / POST /oauth2/end_session エンドポイントを登録する
     *
     * RP-Initiated Logoutに基づくエンドセッションエンドポイント（GETとPOSTの両方が仕様上の必須）
     * - id_token_hint（推奨）: 以前発行されたID Tokenで、ユーザー/クライアントを特定
     * - client_id（オプション）: id_token_hintと併用した場合、ID Tokenのaudに含まれることを検証
     * - post_logout_redirect_uri（オプション）: ログアウト後のリダイレクト先
     * - state（オプション）: リダイレクトに含めるstate値
     */
    fun Route.endSessionRouter() {
        get("/end_session") {
            handleEndSession(call.request.queryParameters)
        }
        post("/end_session") {
            // POSTはフォームシリアライズ（application/x-www-form-urlencoded）で受け取る
            handleEndSession(call.receiveParameters())
        }
    }

    /**
     * エンドセッション要求を処理する
     * GETとPOSTで挙動が食い違わないよう、パラメータの取得元だけを呼び出し側で分ける
     *
     * @param parameters リクエストパラメータ（クエリまたはフォーム）
     */
    private suspend fun RoutingContext.handleEndSession(parameters: Parameters) {
        val idTokenHint = parameters["id_token_hint"]
        val requestedClientId = parameters["client_id"]
        val postLogoutRedirectUri = parameters["post_logout_redirect_uri"]
        val state = parameters["state"]

        // id_token_hintが提供された場合、検証してクライアントを特定
        var validatedClientId: String? = null
        if (idTokenHint != null) {
            val audience = verifyIdTokenHint(idTokenHint)
            if (audience == null) {
                // ID Tokenが無効な場合、エラーレスポンス
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Invalid id_token_hint")
                return
            }
            validatedClientId = when (val selected = selectClientId(audience, requestedClientId)) {
                is Either.Left -> {
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, selected.value)
                    return
                }
                is Either.Right -> selected.value
            }
            // テレメトリ: 検証済みクライアントIDをサーバースパンに記録する
            Span.current().setAttribute(TelemetryAttributes.CLIENT_ID, validatedClientId)
        }

        // リダイレクト先がない場合、ログアウト完了メッセージを返す
        if (postLogoutRedirectUri == null) {
            call.respond(HttpStatusCode.OK, mapOf("message" to "Logout successful"))
            return
        }

        // client_idは署名されていないため、それだけではリダイレクト先の正当性を確認する根拠にしない
        if (validatedClientId == null) {
            call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "id_token_hint is required when post_logout_redirect_uri is specified")
            return
        }

        // クライアントの登録済みリダイレクトURIと照合
        if (!isRegisteredPostLogoutRedirectUri(validatedClientId, postLogoutRedirectUri)) {
            call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Invalid post_logout_redirect_uri")
            return
        }

        // post_logout_redirect_uriにリダイレクト（stateがあれば付与）
        call.respondRedirect(buildPostLogoutRedirectUrl(postLogoutRedirectUri, state))
    }

    /**
     * ID Token Hintを検証してaudクレームを取得する
     *
     * 署名・issuer・種別を必ず検証する（JWT.decode()を使わず、未検証のクレームを扱わない）
     * OIDC RP-Initiated Logout仕様: ログアウト時点でID Tokenが期限切れである可能性があるため、
     * 一定期間内の期限切れは許容する
     *
     * @param idTokenHint 検証するID Token
     * @return audクレームの値、検証失敗時はnull
     */
    private fun verifyIdTokenHint(idTokenHint: String): List<String>? = try {
        JwtProvider.idTokenHintVerifier.verify(idTokenHint).audience.orEmpty()
    } catch (e: JWTVerificationException) {
        // 署名不正・issuer不一致・ID Token以外の種別 → 無効
        null
    }

    /**
     * ID Tokenのaudとclient_idパラメータから対象のクライアントを決定する純粋関数
     *
     * OIDC RP-Initiated Logout 1.0 Section 2: client_idとid_token_hintが両方ある場合、
     * client_idはID Tokenの発行先と一致しなければならない。audは複数値を取り得るため全ての値と照合する
     *
     * @param audience ID Tokenのaudクレームの値
     * @param requestedClientId client_idパラメータの値
     * @return 対象のクライアントID、失敗時はエラーの説明
     */
    internal fun selectClientId(audience: List<String>, requestedClientId: String?): Either<String, String> = when {
        audience.isEmpty() -> "Invalid id_token_hint".left()
        requestedClientId == null -> audience.first().right()
        requestedClientId in audience -> requestedClientId.right()
        else -> "client_id does not match id_token_hint".left()
    }

    /**
     * post_logout_redirect_uriを登録済みクライアントのリダイレクトURIと照合する
     *
     * @param clientId クライアントID
     * @param postLogoutRedirectUri 検証するリダイレクトURI
     * @return 有効な場合true
     */
    private suspend fun isRegisteredPostLogoutRedirectUri(clientId: String, postLogoutRedirectUri: String): Boolean {
        val clientData = ClientData.getClientDataFromDb(clientId) ?: return false

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
