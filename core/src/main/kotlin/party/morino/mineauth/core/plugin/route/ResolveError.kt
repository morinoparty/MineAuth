package party.morino.mineauth.core.plugin.route

/**
 * パラメータ解決中に発生するエラーを表すsealed class
 */
sealed class ResolveError {
    /**
     * 必須のパスパラメータが見つからない
     * @property name パラメータ名
     */
    data class MissingPathParameter(val name: String) : ResolveError()

    /**
     * リクエストボディの形式が不正
     * @property cause 原因となった例外
     */
    data class InvalidBodyFormat(val cause: Throwable) : ResolveError()

    /**
     * 認証が必要だが認証情報がない
     * @property message エラーメッセージ
     */
    data class AuthenticationRequired(val message: String) : ResolveError()

    /**
     * 指定されたプレイヤーが見つからない
     * @property uuid プレイヤーUUID
     */
    data class PlayerNotFound(val uuid: String) : ResolveError()

    /**
     * パラメータの型変換に失敗
     * @property parameterName パラメータ名
     * @property expectedType 期待される型
     * @property actualValue 実際の値
     */
    data class TypeConversionFailed(
        val parameterName: String,
        val expectedType: String,
        val actualValue: String?
    ) : ResolveError()

    /**
     * 他のプレイヤーのデータへのアクセスが拒否された
     * @property reason 拒否理由
     */
    data class AccessDenied(val reason: String) : ResolveError()
}
