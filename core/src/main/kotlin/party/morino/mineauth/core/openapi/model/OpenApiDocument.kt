package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAPI 3.1.0ドキュメントのルートオブジェクト
 *
 * @property openapi OpenAPIバージョン
 * @property info API情報
 * @property servers サーバー一覧
 * @property paths パス定義
 * @property components 再利用可能なコンポーネント
 * @property security グローバルセキュリティ要件
 * @property tags タグ一覧
 */
@Serializable
data class OpenApiDocument(
    val openapi: String = "3.1.0",
    val info: Info,
    val servers: List<Server> = emptyList(),
    val paths: Map<String, PathItem> = emptyMap(),
    val components: Components? = null,
    val security: List<Map<String, List<String>>>? = null,
    val tags: List<Tag>? = null
)
