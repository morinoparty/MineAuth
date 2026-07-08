package party.morino.mineauth.api.annotations

/**
 * PATCHリクエストのエンドポイントを定義するアノテーション
 * @property value エンドポイントのパス
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Patch(val value: String)
