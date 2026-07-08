package party.morino.mineauth.api.annotations

/**
 * リクエストボディをJSONデシリアライズして受け取るパラメータを定義するアノテーション
 * 型は`@Serializable`である必要がある（シリアライザは登録時に解決・検証される）
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Body
