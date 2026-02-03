package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * パスアイテム
 * 特定のパスに対する操作を定義する
 *
 * @property summary パスの概要
 * @property description パスの説明
 * @property get GETオペレーション
 * @property post POSTオペレーション
 * @property put PUTオペレーション
 * @property delete DELETEオペレーション
 * @property patch PATCHオペレーション
 */
@Serializable
data class PathItem(
    val summary: String? = null,
    val description: String? = null,
    val get: Operation? = null,
    val post: Operation? = null,
    val put: Operation? = null,
    val delete: Operation? = null,
    val patch: Operation? = null
)
