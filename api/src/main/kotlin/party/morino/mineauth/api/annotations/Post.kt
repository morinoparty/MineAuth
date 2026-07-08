package party.morino.mineauth.api.annotations

/**
 * POSTリクエストのエンドポイントを定義するアノテーション
 * @property value エンドポイントのパス
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Post(val value: String)
