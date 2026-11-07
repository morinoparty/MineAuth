package party.morino.mineauth.core.web.router.auth.common

import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import java.util.*

interface AuthenticationService {
    /**
     * プレイヤーのパスワードを検証する
     *
     * @param username 提示されたユーザー名
     * @param password 提示されたパスワード
     * @param source リクエストの接続元（Argon2Gateで接続元ごとの検証の枠を割り当てるキー）
     * @return 認証の結果
     */
    suspend fun authenticateUser(username: String, password: String, source: ClientIpKey): AuthenticationResult
    fun getClientData(clientId: String): ClientData?
    fun validateClientAndRedirectUri(clientData: ClientData, redirectUri: String): Boolean
}

sealed class AuthenticationResult {
    data class Success(val uniqueId: UUID) : AuthenticationResult()
    data class Failed(val reason: AuthenticationError) : AuthenticationResult()

    // Argon2の同時実行数か接続元ごとの枠が上限に達していて、パスワードを評価しなかった（ログインの失敗としては扱わない）
    data object VerifierBusy : AuthenticationResult()
}

enum class AuthenticationError {
    PLAYER_NOT_FOUND,
    PLAYER_NOT_REGISTERED,
    INVALID_PASSWORD
}
