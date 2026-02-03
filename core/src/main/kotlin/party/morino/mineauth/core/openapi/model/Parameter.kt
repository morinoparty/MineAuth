package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * パラメータ
 * リクエストパラメータ（path, query, header, cookie）を定義する
 *
 * @property name パラメータ名
 * @property location パラメータの場所（path, query, header, cookie）
 * @property description パラメータの説明
 * @property required 必須かどうか
 * @property schema パラメータのスキーマ
 */
@Serializable
data class Parameter(
    val name: String,
    @SerialName("in")
    val location: String,
    val description: String? = null,
    val required: Boolean = false,
    val schema: Schema? = null
)
