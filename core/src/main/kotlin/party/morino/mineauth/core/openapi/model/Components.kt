package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * コンポーネント
 * 再利用可能なオブジェクトを定義する
 *
 * @property schemas スキーマ定義
 * @property securitySchemes セキュリティスキーム定義
 */
@Serializable
data class Components(
    val schemas: Map<String, Schema>? = null,
    val securitySchemes: Map<String, SecurityScheme>? = null
)

/**
 * セキュリティスキーム
 *
 * @property type スキームタイプ（oauth2, apiKey, http等）
 * @property description スキームの説明
 * @property flows OAuth2フロー定義
 */
@Serializable
data class SecurityScheme(
    val type: String,
    val description: String? = null,
    val flows: OAuthFlows? = null
)

/**
 * OAuth2フロー定義
 *
 * @property authorizationCode 認可コードフロー
 */
@Serializable
data class OAuthFlows(
    val authorizationCode: OAuthFlow? = null
)

/**
 * OAuth2フロー
 *
 * @property authorizationUrl 認可URL
 * @property tokenUrl トークンURL
 * @property scopes スコープ定義
 */
@Serializable
data class OAuthFlow(
    val authorizationUrl: String,
    val tokenUrl: String,
    val scopes: Map<String, String>
)
