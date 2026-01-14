package party.morino.mineauth.api.annotations

/**
 * クエリパラメータを受け取るパラメータを定義するアノテーション
 * Map<String, String>としてすべてのクエリパラメータを取得する場合に使用
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryParams()
