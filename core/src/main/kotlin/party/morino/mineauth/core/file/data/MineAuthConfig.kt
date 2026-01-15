package party.morino.mineauth.core.file.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.*

/**
 * MineAuth 統合設定ファイル
 * Issue #66: 複数の設定ファイルを1つにまとめる
 *
 * 設定ファイルパス: plugins/MineAuth/config.json
 */
@Serializable
data class MineAuthConfig(
    // サーバー設定
    val server: ServerConfig = ServerConfig(),

    // JWT/OIDC設定
    val jwt: JwtConfig = JwtConfig(),

    // OAuth設定
    val oauth: OAuthConfig = OAuthConfig(),

    // データベース設定
    val database: DatabaseConfig = DatabaseConfig.SQLite()
)

/**
 * サーバー設定
 */
@Serializable
data class ServerConfig(
    // 外部公開URL（OIDC Discoveryなどで使用）
    val baseUrl: String = "https://api.example.com",

    // HTTPサーバーポート
    val port: Int = 8080,

    // SSL設定（nullの場合はHTTPのみ）
    val ssl: SSLConfigData? = null,

    // OIDC email クレーム用フォーマット（nullの場合はemailクレームを返さない）
    // プレースホルダー: <uuid>, <username>
    // 例: "<uuid>-<username>@example.com"
    val emailFormat: String? = null
)

/**
 * JWT/OIDC設定
 */
@Serializable
data class JwtConfig(
    // JWTのissuer（通常はbaseUrlと同じ）
    val issuer: String = "https://api.example.com",

    // JWTのrealm
    val realm: String = "example.com",

    // 秘密鍵ファイル名（pluginsDir相対）
    val privateKeyFile: String = "privateKey.pem",

    // JWKのキーID
    val keyId: @Serializable(with = UUIDSerializer::class) UUID = UUID.randomUUID()
)

/**
 * OAuth設定
 */
@Serializable
data class OAuthConfig(
    // 認可画面に表示するアプリケーション名
    val applicationName: String = "MineAuth",

    // 認可画面に表示するロゴURL
    val logoUrl: String = "/assets/lock.svg"
)
