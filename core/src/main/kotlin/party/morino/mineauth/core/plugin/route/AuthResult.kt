package party.morino.mineauth.core.plugin.route

import java.util.UUID

/**
 * 認証結果を表すsealed class
 * プレイヤートークンとサービストークンで異なる結果を返す
 */
sealed class AuthResult {
    /**
     * プレイヤー認証の結果
     * @property playerUuid プレイヤーのMinecraft UUID
     */
    data class PlayerAuth(val playerUuid: UUID) : AuthResult()

    /**
     * サービスアカウント認証の結果
     * @property accountId サービスアカウントのID
     */
    data class ServiceAuth(val accountId: String) : AuthResult()
}
