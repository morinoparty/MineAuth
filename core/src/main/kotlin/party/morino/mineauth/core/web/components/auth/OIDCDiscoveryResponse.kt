package party.morino.mineauth.core.web.components.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import party.morino.mineauth.core.file.data.JWTConfigData

/**
 * OpenID Connect Discovery Response
 * OpenID Connect Discovery 1.0 準拠
 *
 * @see https://openid.net/specs/openid-connect-discovery-1_0.html
 */
@Serializable
data class OIDCDiscoveryResponse(
    // 必須: Issuer Identifier
    val issuer: String,

    // 必須: Authorization Endpoint
    @SerialName("authorization_endpoint")
    val authorizationEndpoint: String,

    // 必須: Token Endpoint
    @SerialName("token_endpoint")
    val tokenEndpoint: String,

    // 推奨: UserInfo Endpoint
    @SerialName("userinfo_endpoint")
    val userinfoEndpoint: String,

    // 必須: JWKs URI
    @SerialName("jwks_uri")
    val jwksUri: String,

    // 必須: サポートするレスポンスタイプ
    @SerialName("response_types_supported")
    val responseTypesSupported: List<String>,

    // 必須: サポートするサブジェクトタイプ
    @SerialName("subject_types_supported")
    val subjectTypesSupported: List<String>,

    // 必須: IDトークンの署名アルゴリズム
    @SerialName("id_token_signing_alg_values_supported")
    val idTokenSigningAlgValuesSupported: List<String>,

    // 推奨: サポートするスコープ
    @SerialName("scopes_supported")
    val scopesSupported: List<String>,

    // 推奨: トークンエンドポイントの認証方法
    @SerialName("token_endpoint_auth_methods_supported")
    val tokenEndpointAuthMethodsSupported: List<String>,

    // 推奨: サポートするクレーム
    @SerialName("claims_supported")
    val claimsSupported: List<String>,

    // 推奨: サポートするグラントタイプ
    @SerialName("grant_types_supported")
    val grantTypesSupported: List<String>,

    // 推奨: コードチャレンジメソッド（PKCE）
    @SerialName("code_challenge_methods_supported")
    val codeChallengeMethodsSupported: List<String>,

    // RFC 7662: Token Introspection Endpoint
    @SerialName("introspection_endpoint")
    val introspectionEndpoint: String,

    // RFC 7662: Introspection Endpointで使えるクライアント認証方法（Confidentialクライアントのみ）
    @SerialName("introspection_endpoint_auth_methods_supported")
    val introspectionEndpointAuthMethodsSupported: List<String>,

    // RFC 7009: Revocation Endpoint
    @SerialName("revocation_endpoint")
    val revocationEndpoint: String,

    // RFC 7009: Revocation Endpointで使えるクライアント認証方法（Confidentialクライアントのみ）
    @SerialName("revocation_endpoint_auth_methods_supported")
    val revocationEndpointAuthMethodsSupported: List<String>,

    // OIDC RP-Initiated Logout: End Session Endpoint
    @SerialName("end_session_endpoint")
    val endSessionEndpoint: String,

    // 任意: サポートするレスポンスモード（認可コードはクエリで返す）
    @SerialName("response_modes_supported")
    val responseModesSupported: List<String>,

    // 任意: request_uriパラメータのサポート有無
    // 省略するとtrue扱いになるため、未対応であることを明示する（OIDC Discovery 1.0 Section 3）
    @SerialName("request_uri_parameter_supported")
    val requestUriParameterSupported: Boolean
) {
    companion object {
        // Introspection/Revocationで使えるクライアント認証方法（authenticateConfidentialClientで検証）
        private val CONFIDENTIAL_CLIENT_AUTH_METHODS = listOf("client_secret_basic", "client_secret_post")

        /**
         * baseUrlからOIDC Discoveryレスポンスを生成
         *
         * baseUrlはそのままissuerになるため、トークンのissと同じ値を渡すこと
         * （OIDC Discovery 1.0 Section 4.3: issuerとID Tokenのissは一致必須）
         *
         * @param baseUrl サーバーのベースURL（= issuer）
         * @param emailEnabled emailスコープが有効かどうか（emailFormatが設定されている場合true）
         * @param rolesEnabled rolesスコープが有効かどうか（LuckPermsがインストールされている場合true）
         */
        fun fromBaseUrl(
            baseUrl: String,
            emailEnabled: Boolean = false,
            rolesEnabled: Boolean = false
        ): OIDCDiscoveryResponse {
            // トークン発行側と同じ規則で正規化し、issuerとissの表記揺れを防ぐ
            val normalizedUrl = JWTConfigData.normalizeIssuer(baseUrl)

            // 有効なスコープを構築
            val scopes = buildList {
                add("openid")
                add("profile")
                // プラグインAPIをユーザートークンで呼ぶには plugin スコープが必須（AuthenticationHandler で検査）
                add("plugin")
                if (emailEnabled) add("email")
                if (rolesEnabled) add("roles")
            }

            // 有効なクレームを構築
            val claims = buildList {
                // openid
                add("sub")
                // profile
                add("name")
                add("picture")
                add("preferred_username")
                // email
                if (emailEnabled) {
                    add("email")
                    add("email_verified")
                }
                // roles
                if (rolesEnabled) {
                    add("roles")
                }
                // ID Token標準クレーム
                add("iss")
                add("aud")
                add("exp")
                add("iat")
                add("auth_time")
                add("nonce")
                add("at_hash")
            }

            return OIDCDiscoveryResponse(
                issuer = normalizedUrl,
                authorizationEndpoint = "$normalizedUrl/oauth2/authorize",
                tokenEndpoint = "$normalizedUrl/oauth2/token",
                userinfoEndpoint = "$normalizedUrl/oauth2/userinfo",
                jwksUri = "$normalizedUrl/.well-known/jwks.json",
                responseTypesSupported = listOf("code"),
                subjectTypesSupported = listOf("public"),
                idTokenSigningAlgValuesSupported = listOf("RS256"),
                scopesSupported = scopes,
                // TokenRouterはBasic認証ヘッダーとフォームの両方に対応し、Publicクライアントはシークレットなし
                tokenEndpointAuthMethodsSupported = listOf(
                    "client_secret_basic",
                    "client_secret_post",
                    "none"
                ),
                claimsSupported = claims,
                grantTypesSupported = listOf(
                    "authorization_code",
                    "refresh_token"
                ),
                codeChallengeMethodsSupported = listOf("S256"),
                introspectionEndpoint = "$normalizedUrl/oauth2/introspect",
                introspectionEndpointAuthMethodsSupported = CONFIDENTIAL_CLIENT_AUTH_METHODS,
                revocationEndpoint = "$normalizedUrl/oauth2/revoke",
                revocationEndpointAuthMethodsSupported = CONFIDENTIAL_CLIENT_AUTH_METHODS,
                endSessionEndpoint = "$normalizedUrl/oauth2/end_session",
                responseModesSupported = listOf("query"),
                requestUriParameterSupported = false
            )
        }
    }
}
