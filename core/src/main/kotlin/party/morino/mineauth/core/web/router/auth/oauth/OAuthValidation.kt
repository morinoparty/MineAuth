package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.web.components.auth.ClientData
import java.security.MessageDigest
import java.util.*

object OAuthValidation : KoinComponent {

    /**
     * クライアントIDでクライアントデータを取得・検証する
     * DBからクライアント情報を取得
     *
     * @param clientId クライアントID
     * @return クライアントデータ、見つからない場合はnull
     */
    fun validateAndGetClientData(clientId: String?): ClientData? {
        if (clientId == null) return null
        return runBlocking { ClientData.getClientDataFromDb(clientId) }
    }

    /**
     * Validate the redirect URI.
     * 登録されたredirect_uriを正規表現パターンとして使用して検証する
     * 末尾に/.*を追加してサブパスもマッチさせる
     *
     * @param clientData クライアントデータ
     * @param redirectUri 検証するリダイレクトURI
     * @return 有効な場合true
     */
    fun validateRedirectUri(clientData: ClientData, redirectUri: String): Boolean {
        val registeredUri = clientData.redirectUri

        // 完全一致チェック（最も一般的なケース）
        if (registeredUri == redirectUri) {
            return true
        }

        // 登録URIを正規表現パターンに変換
        // 末尾に/がなければ追加し、.*を付けてサブパスを許可
        val pattern = if (registeredUri.endsWith("/")) {
            registeredUri + ".*"
        } else {
            registeredUri + "/.*"
        }

        return try {
            Regex(pattern).matches(redirectUri)
        } catch (e: Exception) {
            false
        }
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