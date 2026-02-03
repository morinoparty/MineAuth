package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * JSONスキーマ
 * データ構造を定義する
 *
 * @property type データ型（string, integer, number, boolean, array, object）
 * @property format 型のフォーマット（int32, int64, double, uuid等）
 * @property description スキーマの説明
 * @property properties オブジェクト型の場合のプロパティ定義
 * @property items 配列型の場合の要素スキーマ
 * @property required 必須プロパティのリスト
 * @property additionalProperties 追加プロパティの許可（true/false）
 * @property nullable null許容かどうか
 * @property enum 列挙値のリスト
 */
@Serializable
data class Schema(
    val type: String? = null,
    val format: String? = null,
    val description: String? = null,
    val properties: Map<String, Schema>? = null,
    val items: Schema? = null,
    val required: List<String>? = null,
    val additionalProperties: Boolean? = null,
    val nullable: Boolean? = null,
    val enum: List<String>? = null
)
