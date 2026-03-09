package party.morino.mineauth.core.web.router.auth.oauth

/**
 * フォームパラメータまたはBasic認証から抽出されたクライアントクレデンシャル
 *
 * @param clientId クライアントID（必須）
 * @param clientSecret クライアントシークレット（Publicクライアントの場合はnull）
 */
data class ClientCredentials(
    val clientId: String,
    val clientSecret: String?
)
