package party.morino.mineauth.core.plugin.route

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * HTTPエラーレスポンスを表すシリアライズ可能なデータクラス
 * Map<String, Any>を避けて、シリアライズ可能な形式でエラー情報を返す
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val details: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        /**
         * Map<String, Any>をMap<String, JsonElement>に変換する
         * サポートされる型: String, Number, Boolean
         */
        fun fromDetails(error: String, details: Map<String, Any>): ErrorResponse {
            val convertedDetails = details.mapValues { (_, value) ->
                when (value) {
                    is String -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            }
            return ErrorResponse(error, convertedDetails)
        }
    }
}
