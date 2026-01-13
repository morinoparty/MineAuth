package party.morino.mineauth.api.annotations

/**
 * 複数のパスパラメータを受け取るパラメータを定義するアノテーション
 * Map<String, String>として複数のパラメータを一度に取得する場合に使用
 *
 * @property value 取得するパラメータ名の配列
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class PathParams(
    val value: Array<String>
)
