package party.morino.mineauth.api.annotations

/**
 * PUTリクエストのエンドポイントを定義するアノテーション
 * @property value エンドポイントのパス
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class PutMapping(
    val value: String
) 