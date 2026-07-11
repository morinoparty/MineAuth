package party.morino.mineauth.core.openapi.generator

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `@SerialName`によるプロパティ名リマップを検証するためのスキーマ生成テスト用DTO
 *
 * 分離クラスローダーで再ロードした際、FQNベースの`@SerialName`検出とリフレクションによる
 * 値読み取りが機能することを確認する。
 */
@Serializable
data class SerialNameSampleDto(
    @SerialName("display_name")
    val displayName: String,
    val count: Int
)
