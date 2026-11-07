package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import io.ktor.server.request.path
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes

/**
 * OAuth 2.0 エラーレスポンス
 * RFC 6749 Section 5.2 準拠
 *
 * @property error エラーコード（RFC 6749で定義された値）
 * @property errorDescription エラーの詳細説明（オプション）
 */
@Serializable
data class OAuthErrorResponse(
    val error: String,
    @SerialName("error_description")
    val errorDescription: String? = null
)

/**
 * RFC 6749で定義されたOAuth 2.0エラーコード
 * Section 5.2 Error Response 準拠
 */
enum class OAuthErrorCode(val code: String) {
    // リクエストに必須パラメータが不足、無効なパラメータ値、重複パラメータ等
    INVALID_REQUEST("invalid_request"),

    // クライアント認証に失敗（client_id不明、認証情報不正等）
    INVALID_CLIENT("invalid_client"),

    // 認可グラントまたはリフレッシュトークンが無効、期限切れ、取り消し済み等
    INVALID_GRANT("invalid_grant"),

    // クライアントがこの方法での認可取得を許可されていない
    UNAUTHORIZED_CLIENT("unauthorized_client"),

    // サーバーがこのgrant_typeをサポートしていない
    UNSUPPORTED_GRANT_TYPE("unsupported_grant_type"),

    // リクエストされたスコープが無効、不明、または不正
    INVALID_SCOPE("invalid_scope"),

    // サーバー内部エラー（RFC 6749 Section 4.1.2.1）
    SERVER_ERROR("server_error"),

    // サーバーがこのresponse_typeをサポートしていない（RFC 6749 Section 4.1.2.1）
    UNSUPPORTED_RESPONSE_TYPE("unsupported_response_type"),

    // prompt=noneだが認証済みのセッションが無い（OIDC Core Section 3.1.2.6）
    LOGIN_REQUIRED("login_required"),

    // requestパラメータをサポートしていない（OIDC Core Section 6.1）
    REQUEST_NOT_SUPPORTED("request_not_supported"),

    // request_uriパラメータをサポートしていない（OIDC Core Section 6.2）
    REQUEST_URI_NOT_SUPPORTED("request_uri_not_supported"),

    // 一時的に処理できない（RFC 6749 Section 4.1.2.1）。流量制限で止めたことを通知するのに使う
    TEMPORARILY_UNAVAILABLE("temporarily_unavailable");

    /**
     * OAuthErrorResponseを生成する
     * どの経路で組み立てられても仕様外の文字を返さないよう、ここで説明文を無害化する
     *
     * @param description エラーの詳細説明
     * @return OAuthErrorResponse
     */
    fun toResponse(description: String? = null): OAuthErrorResponse =
        OAuthErrorResponse(code, sanitizeErrorDescription(description))
}

/**
 * error_descriptionに使えない文字を取り除く
 *
 * RFC 6749 Section 4.1.2.1 / 5.2 は値そのものに %x20-21 / %x23-5B / %x5D-7E 以外の文字を禁止しており、
 * URLエンコードしても要件を満たさない。リクエスト由来の値（スコープなど）が混ざっても確実に落とす
 *
 * @param description 無害化前の説明文
 * @return 許可文字だけの説明文。何も残らない場合は、ABNFが1文字以上を要求するためnullを返す
 */
fun sanitizeErrorDescription(description: String?): String? =
    description
        ?.filter { it in ' '..'!' || it in '#'..'[' || it in ']'..'~' }
        ?.ifEmpty { null }

/**
 * OAuthエラーログ出力用のKoinComponent
 * 拡張関数からKoinのDIを利用するためのヘルパーオブジェクト
 */
private object OAuthErrorLogger : KoinComponent {
    // objectはKoinより長生きするため、by inject()で保持せず毎回取得する（Koinが作り直されても現在のプラグインへ出力する）
    val plugin: MineAuth get() = get()
}

/**
 * クライアント認証失敗時に返すBasicチャレンジ
 * RFC 7617 Section 2: realmは必須。charsetはクレデンシャルをUTF-8で解釈することを示す
 */
internal const val BASIC_CLIENT_AUTH_CHALLENGE = "Basic realm=\"MineAuth\", charset=\"UTF-8\""

/**
 * OAuth 2.0エラーレスポンスを返すための拡張関数
 * RFC 6749 Section 5.2に基づき、適切なHTTPステータスコードを設定
 *
 * @param errorCode エラーコード
 * @param description エラーの詳細説明
 * @param status エラーコードの既定と異なるステータスを返す場合に指定する（トークンエンドポイント以外の認可拒否など）
 */
suspend fun RoutingCall.respondOAuthError(
    errorCode: OAuthErrorCode,
    description: String? = null,
    status: HttpStatusCode? = null
) {
    // RFC 6749 Section 5.2:
    // invalid_client: 401 Unauthorized（WWW-Authenticateヘッダーが必要な場合）
    // その他: 400 Bad Request
    val statusCode = status ?: when (errorCode) {
        OAuthErrorCode.INVALID_CLIENT -> HttpStatusCode.Unauthorized
        OAuthErrorCode.SERVER_ERROR -> HttpStatusCode.InternalServerError
        // JSONを返すエンドポイントでは、HTTPステータスで制限中であることを示す
        OAuthErrorCode.TEMPORARILY_UNAVAILABLE -> HttpStatusCode.TooManyRequests
        else -> HttpStatusCode.BadRequest
    }

    // RFC 6749 Section 5.2: 401応答時はWWW-Authenticateヘッダーを付与
    // 案内できるヘッダー認証はBasicのみなので、client_secret_post等のBody認証の失敗でも同じチャレンジを返す
    if (statusCode == HttpStatusCode.Unauthorized) {
        response.header(HttpHeaders.WWWAuthenticate, BASIC_CLIENT_AUTH_CHALLENGE)
    }

    // RFC 6749 Section 5.2: エラーレスポンスにはキャッシュ禁止ヘッダーを付与
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header(HttpHeaders.Pragma, "no-cache")

    // レスポンスとログで同じ無害化済みの説明文を使う（改行などによるログの偽装も防ぐ）
    val safeDescription = sanitizeErrorDescription(description)

    // エラーログを出力（デバッグ用）
    // クエリにはid_token_hintのようなトークンが含まれるため、パスだけを記録する
    val endpoint = request.path()
    val logMessage = "OAuth error at $endpoint: ${errorCode.code}" +
        (safeDescription?.let { " - $it" } ?: "")
    OAuthErrorLogger.plugin.logger.warning(logMessage)

    // テレメトリ: 現在のスパンにエラーコードを記録する（全OAuthエラーパスの中央フック）
    // トレーシング無効時はNoOp Spanになるため安全
    Span.current()
        .setAttribute(TelemetryAttributes.OAUTH_ERROR_CODE, errorCode.code)
        .setStatus(StatusCode.ERROR)

    respond(statusCode, errorCode.toResponse(safeDescription))
}
