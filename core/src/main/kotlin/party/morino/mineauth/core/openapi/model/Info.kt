package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * API情報
 *
 * @property title APIのタイトル
 * @property description APIの説明
 * @property version APIのバージョン
 * @property license ライセンス情報
 */
@Serializable
data class Info(
    val title: String,
    val description: String? = null,
    val version: String,
    val license: License? = null
)

/**
 * ライセンス情報
 *
 * @property name ライセンス名
 * @property url ライセンスURL
 */
@Serializable
data class License(
    val name: String,
    val url: String? = null
)
