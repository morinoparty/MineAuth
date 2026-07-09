package party.morino.mineauth.api

/**
 * マウント済みエンドポイントの情報
 *
 * @property httpMethod HTTPメソッド
 * @property fullPath ベースパスを含む完全なパス（例: /api/v1/plugins/vault/balance/{player}）
 * @property handlerClass ハンドラークラスの完全修飾名
 * @property functionName ハンドラー関数名
 * @property access エンドポイントのアクセス制御情報
 */
data class RegisteredEndpoint(
    val httpMethod: HttpMethod,
    val fullPath: String,
    val handlerClass: String,
    val functionName: String,
    val access: AccessInfo
)
