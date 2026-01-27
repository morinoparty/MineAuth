package party.morino.mineauth.core.plugin.execution

/**
 * メソッド実行中に発生するエラーを表すsealed class
 * ハンドラーの呼び出しや結果の処理で発生するエラーを型安全に表現する
 */
sealed class ExecutionError {
    /**
     * メソッドの呼び出しに失敗した場合
     * @property cause 原因となった例外
     */
    data class InvocationFailed(val cause: Throwable) : ExecutionError()

    /**
     * Javaメソッドへの変換に失敗した場合
     * @property methodName メソッド名
     */
    data class MethodNotFound(val methodName: String) : ExecutionError()

    /**
     * 引数の型が一致しない場合
     * @property methodName メソッド名
     * @property expectedTypes 期待される型のリスト
     * @property actualTypes 実際の型のリスト
     */
    data class ArgumentTypeMismatch(
        val methodName: String,
        val expectedTypes: List<String>,
        val actualTypes: List<String>
    ) : ExecutionError()

    /**
     * HTTP エラーがスローされた場合
     * @property status HTTPステータスコード
     * @property message エラーメッセージ
     * @property details 詳細情報
     */
    data class HttpErrorThrown(
        val status: Int,
        val message: String,
        val details: Map<String, Any>?
    ) : ExecutionError()

    /**
     * 予期しないエラーが発生した場合
     * @property message エラーメッセージ
     * @property cause 原因となった例外（存在する場合）
     */
    data class UnexpectedError(
        val message: String,
        val cause: Throwable? = null
    ) : ExecutionError()
}
