package party.morino.mineauth.core.web.components.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OAuth2.0/OIDC トークンレスポンス
 * RFC 6749 Section 5.1 および OpenID Connect Core 1.0 Section 3.1.3.3 準拠
 */
@Serializable
data class TokenData(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String,
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("refresh_token") val refreshToken: String,
    // OIDC ID Token: scopeに"openid"が含まれる場合のみ発行
    @SerialName("id_token") val idToken: String? = null,
    // 注: stateはRFC 6749 Section 5.1により、トークンレスポンスには含めない
    // stateはリダイレクト時（認可コードと共に）のみ返却される
)