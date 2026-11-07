package party.morino.mineauth.core.web.router.auth.oauth

/**
 * クライアント認証の失敗情報
 * OAuthエラーコードとメッセージを保持し、呼び出し元でレスポンスに変換する
 *
 * @param errorCode OAuthエラーコード（invalid_request, invalid_client等）
 * @param message エラーの詳細メッセージ
 * @param kind 失敗の種類。流量制限のバックオフに数えるかと、応答の仕方を決める
 */
data class ClientAuthFailure(
    val errorCode: OAuthErrorCode,
    val message: String,
    val kind: ClientAuthFailureKind = ClientAuthFailureKind.NOT_VERIFIED
) {
    /**
     * 認証情報を推測した試行として、クライアント認証のバックオフに数えるべき失敗かどうか
     * 照合していない失敗まで数えると、シークレットを持たないPublicクライアントの正規の利用で
     * 利用者のIPがブロックされてしまうので、実際に拒否した場合だけを数える
     */
    val countsAsFailedAttempt: Boolean
        get() = kind == ClientAuthFailureKind.CREDENTIALS_REJECTED
}
