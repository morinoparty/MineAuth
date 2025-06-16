package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.api.utils.json
import party.morino.mineauth.core.web.components.auth.ClientData
import java.security.MessageDigest
import java.util.*

object OAuthValidation : KoinComponent {
    private val plugin: MineAuth by inject()

    fun validateAndGetClientData(clientId: String?): ClientData? {
        if (clientId == null) return null
        
        val clientDataFile = plugin.dataFolder.resolve("clients").resolve(clientId).resolve("data.json")
        if (!clientDataFile.exists()) return null
        
        return json.decodeFromString(clientDataFile.readText())
    }

    fun validateRedirectUri(clientData: ClientData, redirectUri: String?): Boolean {
        if (redirectUri == null) return false
        
        val recordRedirectUri = if (clientData.redirectUri.endsWith("/")) {
            clientData.redirectUri
        } else {
            clientData.redirectUri + "/"
        }
        
        return redirectUri.startsWith(recordRedirectUri)
    }

    fun validatePKCE(codeChallenge: String?, codeChallengeMethod: String?): Boolean {
        if (codeChallenge == null || codeChallengeMethod != "S256") return false
        return true
    }

    fun validateCodeVerifier(codeChallenge: String, codeVerifier: String): Boolean {
        val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
        val base64 = Base64.getUrlEncoder().encodeToString(hash).replace("=", "")
        return codeChallenge == base64
    }

    fun buildErrorRedirectUri(redirectUri: String, error: String, errorDescription: String, state: String): String {
        val uri = URLBuilder()
        uri.takeFrom(redirectUri)
        uri.parameters.apply {
            append("error", error)
            append("error_description", errorDescription)
            append("state", state)
        }
        return uri.buildString()
    }

    fun buildSuccessRedirectUri(redirectUri: String, code: String, state: String): String {
        val uri = URLBuilder()
        uri.takeFrom(redirectUri)
        uri.parameters.apply {
            append("code", code)
            append("state", state)
        }
        return uri.buildString()
    }
}