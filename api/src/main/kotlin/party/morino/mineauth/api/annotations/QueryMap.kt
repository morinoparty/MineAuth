package party.morino.mineauth.api.annotations

/**
 * 全クエリパラメータを`Map<String, String>`として受け取るパラメータを定義するアノテーション
 * 型付きの`@Query`で表現できない動的なクエリを扱うためのエスケープハッチ
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class QueryMap
