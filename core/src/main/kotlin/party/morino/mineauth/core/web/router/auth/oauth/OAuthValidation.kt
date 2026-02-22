package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
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

    // RFC 7636 Section 4.1: code_verifierの許容文字種 [A-Za-z0-9-._~]
    private val CODE_VERIFIER_PATTERN = Regex("^[A-Za-z0-9\\-._~]{43,128}$")

    /**
     * code_verifierの形式を検証する
     * RFC 7636 Section 4.1: 43〜128文字、[A-Za-z0-9-._~]のみ許可
     *
     * @param codeVerifier 検証するcode_verifier
     * @return 有効な場合true
     */
    fun isValidCodeVerifierFormat(codeVerifier: String): Boolean {
        return CODE_VERIFIER_PATTERN.matches(codeVerifier)
    }

    /**
     * code_verifierとcode_challengeを検証する（S256方式）
     * RFC 7636 Section 4.6: SHA-256ハッシュとBase64URLエンコードで比較
     *
     * @param codeChallenge 認可時に送信されたcode_challenge
     * @param codeVerifier トークン交換時に送信されたcode_verifier
     * @return 一致する場合true
     */
    fun validateCodeVerifier(codeChallenge: String, codeVerifier: String): Boolean {
        // RFC 7636 Section 4.1: code_verifierの長さ・文字種を検証
        if (!isValidCodeVerifierFormat(codeVerifier)) return false
        // RFC 7636 Section 4.6: ASCII エンコーディングを明示的に指定
        val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        val base64 = Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
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

    /**
     * Authorization: Basic ヘッダーからclient_idとclient_secretを取得する
     * RFC 6749 Section 2.3.1 (client_secret_basic) 準拠
     *
     * @return Pair(client_id, client_secret) または null（ヘッダーが存在しない/不正な場合）
     */
    fun RoutingContext.extractBasicCredentials(): Pair<String, String>? {
        val authHeader = call.request.header(HttpHeaders.Authorization) ?: return null
        if (!authHeader.startsWith("Basic ", ignoreCase = true)) return null
        return try {
            // Base64デコードして "client_id:client_secret" 形式を分割
            val decoded = String(Base64.getDecoder().decode(authHeader.substring(6)), Charsets.UTF_8)
            val colonIndex = decoded.indexOf(':')
            if (colonIndex < 0) return null
            val id = decoded.substring(0, colonIndex)
            val secret = decoded.substring(colonIndex + 1)
            Pair(id, secret)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}