package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    INVALID_SCOPE("invalid_scope");

    /**
     * OAuthErrorResponseを生成する
     *
     * @param description エラーの詳細説明
     * @return OAuthErrorResponse
     */
    fun toResponse(description: String? = null): OAuthErrorResponse =
        OAuthErrorResponse(code, description)
}

/**
 * OAuth 2.0エラーレスポンスを返すための拡張関数
 * RFC 6749 Section 5.2に基づき、適切なHTTPステータスコードを設定
 *
 * @param errorCode エラーコード
 * @param description エラーの詳細説明
 */
suspend fun RoutingCall.respondOAuthError(
    errorCode: OAuthErrorCode,
    description: String? = null
) {
    // RFC 6749 Section 5.2:
    // invalid_client: 401 Unauthorized（WWW-Authenticateヘッダーが必要な場合）
    // その他: 400 Bad Request
    val statusCode = when (errorCode) {
        OAuthErrorCode.INVALID_CLIENT -> HttpStatusCode.Unauthorized
        else -> HttpStatusCode.BadRequest
    }
    respond(statusCode, errorCode.toResponse(description))
}
