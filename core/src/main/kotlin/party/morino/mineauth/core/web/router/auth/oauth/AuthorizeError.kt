package party.morino.mineauth.core.web.router.auth.oauth

/**
 * 認可リクエストの検証エラー
 *
 * redirect_uriを信頼できるかどうかで応答方法が根本的に変わるため、型で分けて混同できないようにする
 */
sealed interface AuthorizeError {
    /**
     * client_idまたはredirect_uriが欠落・不正なエラー
     * RFC 6749 Section 4.1.2.1: 不正なredirect_uriへ自動でリダイレクトしてはならないので、利用者に直接表示する
     *
     * @property message 利用者に表示するメッセージ（リクエスト由来の値を含めないこと）
     */
    data class ShowError(val message: String) : AuthorizeError

    /**
     * redirect_uriの検証後に見つかったエラー
     * RFC 6749 Section 4.1.2.1: 検証済みのredirect_uriへのリダイレクトでクライアントに通知する
     *
     * @property redirectUri 検証済みのリダイレクトURI
     * @property code エラーコード
     * @property description エラーの説明
     * @property state リクエストのstate。リクエストに無かった場合はnull
     */
    data class RedirectWithError(
        val redirectUri: String,
        val code: OAuthErrorCode,
        val description: String,
        val state: String?,
    ) : AuthorizeError
}
