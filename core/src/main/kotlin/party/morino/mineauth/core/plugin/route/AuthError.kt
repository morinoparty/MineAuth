package party.morino.mineauth.core.plugin.route

/**
 * 認証・認可エラーを表すsealed class
 */
sealed class AuthError {
    /**
     * 認証されていない
     */
    data object NotAuthenticated : AuthError()

    /**
     * トークンが無効
     * @property reason 無効な理由
     */
    data class InvalidToken(val reason: String) : AuthError()

    /**
     * パーミッションが不足
     * @property permission 必要なパーミッション
     */
    data class PermissionDenied(val permission: String) : AuthError()
}
