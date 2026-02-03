package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * オペレーション
 * HTTPメソッドに対応する操作を定義する
 *
 * @property summary オペレーションの概要
 * @property description オペレーションの詳細説明
 * @property operationId 一意のオペレーション識別子
 * @property tags 関連タグのリスト
 * @property parameters パラメータリスト
 * @property requestBody リクエストボディ
 * @property responses レスポンス定義
 * @property security セキュリティ要件
 */
@Serializable
data class Operation(
    val summary: String? = null,
    val description: String? = null,
    val operationId: String? = null,
    val tags: List<String>? = null,
    val parameters: List<Parameter>? = null,
    val requestBody: RequestBody? = null,
    val responses: Map<String, Response>,
    val security: List<Map<String, List<String>>>? = null
)
