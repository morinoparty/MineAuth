package party.morino.mineauth.core.web.router.auth.oauth

import com.password4j.Password
import kotlinx.coroutines.Dispatchers
import org.bukkit.Bukkit
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.database.UserAuthData
import party.morino.mineauth.core.database.UserAuthData.uuid
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import party.morino.mineauth.core.web.router.auth.common.AuthenticationService
import java.util.*

object OAuthService : AuthenticationService, KoinComponent {
    
    override suspend fun authenticateUser(username: String, password: String): AuthenticationResult {
        val offlinePlayer = Bukkit.getOfflinePlayer(username)
        if (!offlinePlayer.hasPlayedBefore()) {
            return AuthenticationResult.Failed(AuthenticationError.PLAYER_NOT_FOUND)
        }
        
        val uniqueId = offlinePlayer.uniqueId
        val exist = newSuspendedTransaction(Dispatchers.IO) {
            UserAuthData.selectAll().where { uuid eq uniqueId.toString() }.count() > 0
        }
        if (!exist) {
            return AuthenticationResult.Failed(AuthenticationError.PLAYER_NOT_REGISTERED)
        }
        
        val hashedPassword = newSuspendedTransaction {
            UserAuthData.selectAll().where { uuid eq uniqueId.toString() }.first()[UserAuthData.password]
        }
        val check = Password.check(password, hashedPassword).addPepper().withArgon2()
        if (!check) {
            return AuthenticationResult.Failed(AuthenticationError.INVALID_PASSWORD)
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