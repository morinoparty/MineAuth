package party.morino.mineauth.api.annotations

/**
 * パスパラメータを受け取るパラメータを定義するアノテーション
 * 例: `@Get("/shops/{shopId}")` の `shopId` を取得する場合
 *
 * サポートされる型: String, Int, Long, Double, Float, Boolean, UUID
 * （それ以外の型は登録時にエラーとなる）
 *
 * @property value 取得するパスセグメント名（パスに`{セグメント名}`が存在しないと登録時エラー）
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Path(val value: String)
