package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * レスポンス
 *
 * @property description レスポンスの説明
 * @property content コンテンツタイプごとのメディアタイプ定義
 */
@Serializable
data class Response(
    val description: String,
    val content: Map<String, MediaType>? = null
)
