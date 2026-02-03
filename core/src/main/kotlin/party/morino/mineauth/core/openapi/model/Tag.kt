package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * タグ情報
 * エンドポイントをグループ化するために使用
 *
 * @property name タグ名
 * @property description タグの説明
 */
@Serializable
data class Tag(
    val name: String,
    val description: String? = null
)
