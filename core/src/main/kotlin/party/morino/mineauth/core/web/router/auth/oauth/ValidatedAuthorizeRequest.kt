package party.morino.mineauth.core.web.router.auth.oauth

/**
 * 検証を通過した認可リクエスト
 * 認可画面の表示とログインの処理は、生のパラメータではなくこの値だけを使う
 *
 * @property clientId 登録済みのクライアントID
 * @property clientName 認可画面に表示するクライアント名
 * @property redirectUri 登録情報と照合済みのリダイレクトURI
 * @property state リクエストのstate
 * @property grantedScopes リクエストから解決した、実際に付与するスコープ
 * @property codeChallenge PKCEのcode_challenge
 * @property codeChallengeMethod PKCEのcode_challenge_method（S256のみ）
 * @property nonce OIDCのnonce。リクエストに含まれていた場合のみ
 */
data class ValidatedAuthorizeRequest(
    val clientId: String,
    val clientName: String,
    val redirectUri: String,
    val state: String,
    val grantedScopes: Set<OAuthScope>,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val nonce: String?,
) {
    // 認可画面と認可コードが保持する、付与スコープのスペース区切り文字列
    val scope: String
        get() = OAuthScope.format(grantedScopes)
}
