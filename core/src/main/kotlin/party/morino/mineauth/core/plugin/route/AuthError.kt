package party.morino.mineauth.core.plugin.route

import party.morino.mineauth.api.CallerType

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
     * トークン種別がエンドポイントの許可設定と一致しない
     * 例: USER専用エンドポイントにサービストークンでアクセスした場合
     * @property allowed 許可されているトークン種別
     */
    data class WrongTokenType(val allowed: Set<CallerType>) : AuthError()

    /**
     * パーミッションが不足
     * @property permission 必要なパーミッション
     */
    data class PermissionDenied(val permission: String) : AuthError()

    /**
     * プレイヤーがオフラインのためパーミッション評価ができない
     * パーミッション不足とは区別してクライアントに通知する
     * @property permission 評価しようとしたパーミッション
     */
    data class PlayerOffline(val permission: String) : AuthError()
}
