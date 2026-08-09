package party.morino.mineauth.core.web.router.auth.oauth

/**
 * リフレッシュトークンローテーションの失敗情報
 * OAuthエラーコードとメッセージを保持し、呼び出し元でレスポンスに変換する
 *
 * @param errorCode OAuthエラーコード（invalid_grant, server_error等）
 * @param message エラーの詳細メッセージ
 */
data class TokenRotationFailure(
    val errorCode: OAuthErrorCode,
    val message: String
)
