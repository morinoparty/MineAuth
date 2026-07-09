package party.morino.mineauth.api.annotations

/**
 * 単一のクエリパラメータを受け取るパラメータを定義するアノテーション
 *
 * サポートされる型: String, Int, Long, Double, Float, Boolean, UUID
 * Kotlinの型がnullableの場合は省略可能（省略時null）、
 * non-nullableの場合は必須（省略時400 Bad Request）となる。
 *
 * ```kotlin
 * @Get("/shops")
 * @Public
 * fun listShops(@Query("limit") limit: Int?, @Query("cursor") cursor: String?): ShopsResponse
 * ```
 *
 * @property value 取得するクエリパラメータ名
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Query(val value: String)
