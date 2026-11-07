package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.database.UserAuthData
import party.morino.mineauth.core.database.UserAuthData.uuid
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.utils.PasswordVerification
import party.morino.mineauth.core.utils.PlayerPasswordHasher
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.common.AuthenticationService
import party.morino.mineauth.core.web.telemetry.withDatabaseSpan
import java.util.*

object OAuthService : AuthenticationService, KoinComponent {
    override suspend fun authenticateUser(username: String, password: String, source: ClientIpKey): AuthenticationResult {
        val offlinePlayer = Bukkit.getOfflinePlayer(username)
        if (!offlinePlayer.hasPlayedBefore()) {
            return AuthenticationResult.Failed(AuthenticationError.PLAYER_NOT_FOUND)
        }
        
        val uniqueId = offlinePlayer.uniqueId
        val exist = withDatabaseSpan("user_auth_data", "select") {
            newSuspendedTransaction(Dispatchers.IO) {
                UserAuthData.selectAll().where { uuid eq uniqueId.toString() }.count() > 0
            }
        }
        if (!exist) {
            return AuthenticationResult.Failed(AuthenticationError.PLAYER_NOT_REGISTERED)
        }

        val hashedPassword = withDatabaseSpan("user_auth_data", "select") {
            newSuspendedTransaction {
                UserAuthData.selectAll().where { uuid eq uniqueId.toString() }.first()[UserAuthData.password]
            }
        }
        // /mineauth reload で設定が変わると置き換わるため、保持せず認証のたびに取得する
        val passwordHasher = get<PlayerPasswordHasher>()
        // Argon2はメモリを大きく使うので、プロセス全体のゲートで同時実行数を制限する
        // 許可はハッシュの計算の間だけ持ち、前後のDBアクセスには持ち越さない
        // 接続元ごとの枠も使い、1つの接続元からの大量のログインが他の接続元の検証を待たせないようにする
        val argon2Gate = get<Argon2Gate>()
        val verification = argon2Gate.withPermit(source) { passwordHasher.verify(password, hashedPassword) }
            .getOrElse { return AuthenticationResult.VerifierBusy }
        when (verification) {
            PasswordVerification.MATCHED -> Unit
            // 旧パラメータのハッシュは現在の設定で再ハッシュして移行する
            PasswordVerification.MATCHED_NEEDS_REHASH -> {
                // 再ハッシュは移行のための付随処理なので、混雑している時は見送り、次回のログインで移行する
                // パスワードは照合済みなので、ログイン自体は成功させる
                // 再ハッシュも同じ接続元の枠で数え、1つの接続元が使える検証の数を増やさない
                argon2Gate.withPermit(source) { passwordHasher.hash(password) }.onRight { rehashed ->
                    withDatabaseSpan("user_auth_data", "update") {
                        newSuspendedTransaction(Dispatchers.IO) {
                            UserAuthData.update({ uuid eq uniqueId.toString() }) {
                                it[UserAuthData.password] = rehashed
                            }
                        }
                    }
                }
            }
            PasswordVerification.NOT_MATCHED -> return AuthenticationResult.Failed(AuthenticationError.INVALID_PASSWORD)
        }
        
        return AuthenticationResult.Success(uniqueId)
    }
    
    override fun getClientData(clientId: String): ClientData? {
        return OAuthValidation.validateAndGetClientData(clientId)
    }
    
    override fun validateClientAndRedirectUri(clientData: ClientData, redirectUri: String): Boolean {
        return OAuthValidation.validateRedirectUri(clientData, redirectUri)
    }
}