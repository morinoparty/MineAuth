package party.morino.mineauth.core.web.router.auth.common

import party.morino.mineauth.core.web.components.auth.ClientData
import java.util.*

interface AuthenticationService {
    suspend fun authenticateUser(username: String, password: String): AuthenticationResult
    fun getClientData(clientId: String): ClientData?
    fun validateClientAndRedirectUri(clientData: ClientData, redirectUri: String): Boolean
}

sealed class AuthenticationResult {
    data class Success(val uniqueId: UUID) : AuthenticationResult()
    data class Failed(val reason: AuthenticationError) : AuthenticationResult()
}

enum class AuthenticationError {
    PLAYER_NOT_FOUND,
    PLAYER_NOT_REGISTERED,
    INVALID_PASSWORD
}