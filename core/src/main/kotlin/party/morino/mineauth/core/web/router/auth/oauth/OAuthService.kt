package party.morino.mineauth.core.web.router.auth.oauth

import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.database.UserAuthData
import party.morino.mineauth.core.database.UserAuthData.uuid
import party.morino.mineauth.core.utils.PasswordVerification
import party.morino.mineauth.core.utils.PlayerPasswordHasher
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.common.AuthenticationService
import party.morino.mineauth.core.web.telemetry.withDatabaseSpan
import java.util.*

object OAuthService : AuthenticationService, KoinComponent {
    private val passwordHasher: PlayerPasswordHasher by inject()
    
    override suspend fun authenticateUser(username: String, password: String): AuthenticationResult {
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
        when (passwordHasher.verify(password, hashedPassword)) {
            PasswordVerification.MATCHED -> Unit
            // 旧pepper・旧パラメータのハッシュは現在の設定で再ハッシュして移行する
            PasswordVerification.MATCHED_NEEDS_REHASH -> {
                val rehashed = passwordHasher.hash(password)
                withDatabaseSpan("user_auth_data", "update") {
                    newSuspendedTransaction(Dispatchers.IO) {
                        UserAuthData.update({ uuid eq uniqueId.toString() }) {
                            it[UserAuthData.password] = rehashed
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