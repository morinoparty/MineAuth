package party.morino.mineauth.core.plugin.auth

import party.morino.mineauth.api.auth.Principal

/**
 * サービスアカウントトークンで認証されたサービスのPrincipal実装
 *
 * @property accountId サービスアカウントのID
 * @property scopes トークンに付与されたスコープ（現状サービストークンにはスコープがないため空）
 * @property clientId OAuthクライアントID（サービストークンにはないためnull）
 */
class ServicePrincipal(
    override val accountId: String,
    override val scopes: Set<String> = emptySet(),
    override val clientId: String? = null
) : Principal.Service
