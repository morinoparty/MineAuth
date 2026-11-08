package party.morino.mineauth.core.web.router.auth.oauth

/**
 * トークンの状態検証に失敗した理由
 *
 * @property authResult スパン属性 mineauth.auth.result に記録する値
 */
enum class TokenStateFailure(val authResult: String) {
    // 検証対象の種別とトークンの種別が一致しない、または状態を報告しない種別である
    WRONG_TOKEN_TYPE("wrong_token_type"),

    // トークンを発行したOAuthクライアントが存在しない
    CLIENT_NOT_FOUND("client_not_found"),

    // サービストークンにaccount_idクレームがない
    ACCOUNT_ID_MISSING("account_id_missing"),

    // サービスアカウントが存在しない
    ACCOUNT_NOT_FOUND("account_not_found"),

    // トークンが失効済み、または失効していないことを確認できない
    REVOKED("revoked"),

    // トークンの属するグラントが失効済み、または失効していないことを確認できない
    GRANT_REVOKED("grant_revoked")
}
