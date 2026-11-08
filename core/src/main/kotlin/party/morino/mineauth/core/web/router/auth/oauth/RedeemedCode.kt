package party.morino.mineauth.core.web.router.auth.oauth

/**
 * 使用済みの認可コードの記録
 * リプレイを検知したときに、どのグラントを失効させるかを判断するために保持する
 *
 * @param grantId 認可コードに紐づくグラントID
 * @param clientId 認可コードを発行したクライアントID（他のクライアントによるリプレイの判別に使う）
 * @param expiresAt 認可コード本来の有効期限（Unix timestamp ミリ秒）
 */
data class RedeemedCode(
    val grantId: String,
    val clientId: String,
    val expiresAt: Long
)
