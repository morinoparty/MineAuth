package party.morino.mineauth.api.annotations

/**
 * GETリクエストのエンドポイントを定義するアノテーション
 * @property value エンドポイントのパス（例: "/shops/{shopId}"）
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Get(val value: String)
