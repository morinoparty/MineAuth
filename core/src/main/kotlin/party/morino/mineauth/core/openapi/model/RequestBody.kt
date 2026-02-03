package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * リクエストボディ
 *
 * @property description リクエストボディの説明
 * @property content コンテンツタイプごとのメディアタイプ定義
 * @property required 必須かどうか
 */
@Serializable
data class RequestBody(
    val description: String? = null,
    val content: Map<String, MediaType>,
    val required: Boolean = false
)

/**
 * メディアタイプ
 *
 * @property schema コンテンツのスキーマ
 */
@Serializable
data class MediaType(
    val schema: Schema? = null
)
