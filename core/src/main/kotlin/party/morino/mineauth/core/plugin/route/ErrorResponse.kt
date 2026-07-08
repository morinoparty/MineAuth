package party.morino.mineauth.core.plugin.route

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * HTTPエラーレスポンスを表すシリアライズ可能なデータクラス
 *
 * @property error 人間が読めるエラーメッセージ
 * @property code 機械可読なエラーコード（クライアントの分岐処理用、任意）
 *   フレームワークが返すコード: "authentication_required", "invalid_token",
 *   "wrong_token_type", "access_denied", "player_offline", "not_found", "method_not_allowed"
 * @property details エラーの詳細情報
 */
@Serializable
data class ErrorResponse(
    val error: String,
    val code: String? = null,
    val details: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        /**
         * Map<String, Any>をMap<String, JsonElement>に変換してErrorResponseを生成する
         * サポートされる型: String, Number, Boolean
         */
        fun fromDetails(error: String, details: Map<String, Any>, code: String? = null): ErrorResponse {
            val convertedDetails = details.mapValues { (_, value) ->
                when (value) {
                    is String -> JsonPrimitive(value)
                    is Number -> JsonPrimitive(value)
                    is Boolean -> JsonPrimitive(value)
                    else -> JsonPrimitive(value.toString())
                }
            }
            return ErrorResponse(error, code, convertedDetails)
        }
    }
}
