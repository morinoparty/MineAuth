package party.morino.mineauth.api.annotations

/**
 * パスパラメータを受け取るパラメータを定義するアノテーション
 * @property value 取得するパラメータ名の配列
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class Params(
    val value: Array<String>
)
