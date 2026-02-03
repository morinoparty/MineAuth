package party.morino.mineauth.core.openapi.model

import kotlinx.serialization.Serializable

/**
 * サーバー情報
 *
 * @property url サーバーURL
 * @property description サーバーの説明
 */
@Serializable
data class Server(
    val url: String,
    val description: String? = null
)
