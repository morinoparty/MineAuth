package party.morino.mineauth.api.annotations

/**
 * クラスレベルのパスプレフィックスを定義するアノテーション
 * クラス内の全エンドポイントのパスの前に付与される
 *
 * ```kotlin
 * @Route("/shops")
 * class ShopHandler {
 *     @Get("/{shopId}")  // => GET /shops/{shopId}
 *     ...
 * }
 * ```
 *
 * @property value パスプレフィックス
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Route(val value: String)
