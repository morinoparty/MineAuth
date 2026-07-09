package party.morino.mineauth.api.annotations

/**
 * DELETEリクエストのエンドポイントを定義するアノテーション
 * @property value エンドポイントのパス
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Delete(val value: String)
