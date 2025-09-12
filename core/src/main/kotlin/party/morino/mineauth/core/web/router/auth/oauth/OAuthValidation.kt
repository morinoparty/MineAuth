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

    /**
     * Validate the redirect URI.
     * The redirect URI must start with the registered redirect URI.
     * If the registered redirect URI ends with a slash, it is considered as a directory.
     * If the registered redirect URI does not end with a slash, it is considered as a file.
     *
     * @param clientData The client data.
     * @sample clientData.redirectUri = "https://\\w{6}-example.com/callback/"
     * @param redirectUri The redirect URI to validate.
     * @sample redirectUri = "https://hash-example.com"
     * @return True if the redirect URI is valid, false otherwise.
     */
    fun validateRedirectUri(clientData: ClientData, redirectUri: String): Boolean {
        val recordRedirectUri = if (clientData.redirectUri.endsWith("/")) {
            clientData.redirectUri
        } else {
            clientData.redirectUri + "/"
        } + ".*"
        
        return Regex(recordRedirectUri).matches(redirectUri)
    }

    fun validatePKCE(codeChallenge: String?, codeChallengeMethod: String?): Boolean {
        return !(codeChallenge == null || codeChallengeMethod != "S256")
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