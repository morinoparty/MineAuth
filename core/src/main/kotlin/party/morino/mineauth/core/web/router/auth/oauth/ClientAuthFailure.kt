package party.morino.mineauth.core.web.router.auth.oauth

/**
 * クライアント認証の失敗情報
 * OAuthエラーコードとメッセージを保持し、呼び出し元でレスポンスに変換する
 *
 * @param errorCode OAuthエラーコード（invalid_request, invalid_client等）
 * @param message エラーの詳細メッセージ
 */
data class ClientAuthFailure(
    val errorCode: OAuthErrorCode,
    val message: String
)
