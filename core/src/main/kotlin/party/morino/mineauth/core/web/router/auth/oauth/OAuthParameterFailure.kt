package party.morino.mineauth.core.web.router.auth.oauth

/**
 * OAuthリクエストパラメータの読み取り失敗情報
 * OAuthエラーコードとメッセージを保持し、呼び出し元でレスポンスに変換する
 *
 * @param errorCode OAuthエラーコード（重複パラメータはinvalid_request）
 * @param message エラーの詳細メッセージ
 */
data class OAuthParameterFailure(
    val errorCode: OAuthErrorCode,
    val message: String
)
