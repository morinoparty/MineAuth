package party.morino.mineauth.core.web.router.auth.oauth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * RFC 7662 OAuth 2.0 Token Introspection レスポンス
 *
 * リソースサーバーがトークンの状態とメタデータを確認するためのレスポンス形式
 */
@Serializable
data class IntrospectionResponse(
    // トークンが有効かどうか
    val active: Boolean,

    // トークンのスコープ（スペース区切り）
    val scope: String? = null,

    // トークンを発行したクライアントID
    @SerialName("client_id")
    val clientId: String? = null,

    // トークンの所有者名
    val username: String? = null,

    // トークン種別（"Bearer"）
    @SerialName("token_type")
    val tokenType: String? = null,

    // 有効期限（Unix秒）
    val exp: Long? = null,

    // 発行日時（Unix秒）
    val iat: Long? = null,

    // 有効開始日時（Unix秒）
    val nbf: Long? = null,

    // サブジェクト（プレイヤーUUID）
    val sub: String? = null,

    // 対象オーディエンス
    val aud: String? = null,

    // 発行者
    val iss: String? = null,

    // JWT ID
    val jti: String? = null
)
