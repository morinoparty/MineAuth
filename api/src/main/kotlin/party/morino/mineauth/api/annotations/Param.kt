package party.morino.mineauth.api.annotations

/**
 * 単一のパスパラメータを受け取るパラメータを定義するアノテーション
 * 例: @GetMapping("/shops/{shopId}") の shopId を取得する場合
 *
 * @property value 取得するパラメータ名
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Param(
    val value: String
)
