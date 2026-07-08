package party.morino.mineauth.api.http

/**
 * ハンドラーからHTTPエラーレスポンスを返すための例外
 *
 * ```kotlin
 * throw HttpError(HttpStatus.NOT_FOUND, "Shop not found", code = "shop_not_found")
 * // または fail() ヘルパーを使用
 * fail(HttpStatus.NOT_FOUND, "Shop not found", code = "shop_not_found")
 * ```
 *
 * @property status HTTPステータス
 * @property message 人間が読めるエラーメッセージ
 * @property code 機械可読なエラーコード（クライアントの分岐処理用、任意）
 * @property details エラーの詳細情報
 */
class HttpError(
    val status: HttpStatus,
    override val message: String,
    val code: String? = null,
    val details: Map<String, String> = emptyMap()
) : RuntimeException(message)

/**
 * [HttpError]をスローするヘルパー関数
 * 戻り値型が`Nothing`のため、when式の分岐などでスムーズに使用できる
 *
 * @param status HTTPステータス
 * @param message エラーメッセージ
 * @param code 機械可読なエラーコード（任意）
 * @param details エラーの詳細情報
 */
fun fail(
    status: HttpStatus,
    message: String,
    code: String? = null,
    details: Map<String, String> = emptyMap()
): Nothing = throw HttpError(status, message, code, details)
