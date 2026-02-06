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
    val database: DatabaseConfig = DatabaseConfig.SQLite(),

    // Observability設定（メトリクス・トレーシング）
    val observability: ObservabilityConfig = ObservabilityConfig()
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
    // 例: "<uuid>+<username>@example.com"
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

/**
 * Observability設定（トレーシング・メトリクス）
 * OpenTelemetryを使用してJaegerなどのバックエンドに送信
 */
@Serializable
data class ObservabilityConfig(
    // トレーシングを有効にするかどうか
    val enabled: Boolean = false,

    // OTLPエクスポーター設定のリスト（複数のバックエンドに送信可能）
    val exporters: List<OtlpExporterConfig> = listOf(OtlpExporterConfig()),

    // サービス名（トレースに表示される名前）
    val serviceName: String = "mineauth",

    // メトリクスエンドポイント(/metrics)を有効にするかどうか
    val metricsEnabled: Boolean = true,

    // ヘルスチェックエンドポイント(/health)を有効にするかどうか
    val healthEnabled: Boolean = true
)

/**
 * OTLPエクスポーター設定
 * 各バックエンド（Jaeger, Tempo等）への送信設定
 */
@Serializable
data class OtlpExporterConfig(
    // OTLP通信プロトコル（gRPC/HTTP）
    val protocol: OtlpExporterProtocol = OtlpExporterProtocol.GRPC,

    // OTLPエンドポイント（例: http://localhost:4317）
    val endpoint: String = "http://localhost:4317",

    // 認証用ヘッダー（例: Authorization -> Bearer xxx）
    val headers: Map<String, String> = emptyMap()
)

/**
 * OTLP通信プロトコル
 */
@Serializable
enum class OtlpExporterProtocol {
    // gRPCで送信する
    GRPC,

    // HTTPで送信する
    HTTP
}
