package party.morino.mineauth.core.web.router.auth.oauth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.components.auth.ClientData
import java.security.MessageDigest
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OAuthValidationのテスト
 * 純粋関数を中心にテストする
 */
class OAuthValidationTest {

    @Nested
    @DisplayName("validatePKCE")
    inner class ValidatePKCETest {

        @Test
        @DisplayName("Valid PKCE with S256")
        fun validPKCEWithS256ReturnsTrue() {
            // S256メソッドで有効なcode_challengeを持つ場合はtrue
            val result = OAuthValidation.validatePKCE("valid_code_challenge", "S256")
            assertTrue(result)
        }

        @Test
        @DisplayName("Invalid PKCE with null challenge")
        fun nullCodeChallengeReturnsFalse() {
            // code_challengeがnullの場合はfalse
            val result = OAuthValidation.validatePKCE(null, "S256")
            assertFalse(result)
        }

        @Test
        @DisplayName("Invalid PKCE with wrong method")
        fun invalidMethodReturnsFalse() {
            // S256以外のメソッドはfalse
            val result = OAuthValidation.validatePKCE("valid_code_challenge", "plain")
            assertFalse(result)
        }

        @Test
        @DisplayName("Invalid PKCE with null method")
        fun nullMethodReturnsFalse() {
            // メソッドがnullの場合はfalse
            val result = OAuthValidation.validatePKCE("valid_code_challenge", null)
            assertFalse(result)
        }
    }

    @Nested
    @DisplayName("validateCodeVerifier")
    inner class ValidateCodeVerifierTest {

        @Test
        @DisplayName("Valid code verifier matches challenge")
        fun validCodeVerifierMatchesChallenge() {
            // 正しいcode_verifierからcode_challengeを計算
            val codeVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
            val expectedChallenge = generateCodeChallenge(codeVerifier)

            val result = OAuthValidation.validateCodeVerifier(expectedChallenge, codeVerifier)
            assertTrue(result)
        }

        @Test
        @DisplayName("Invalid code verifier does not match")
        fun invalidCodeVerifierDoesNotMatch() {
            val codeVerifier = "correct_verifier"
            val codeChallenge = generateCodeChallenge(codeVerifier)

            val result = OAuthValidation.validateCodeVerifier(codeChallenge, "wrong_verifier")
            assertFalse(result)
        }

        // code_challengeを生成するヘルパーメソッド
        private fun generateCodeChallenge(codeVerifier: String): String {
            val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray())
            return Base64.getUrlEncoder().encodeToString(hash).replace("=", "")
        }
    }

    @Nested
    @DisplayName("buildErrorRedirectUri")
    inner class BuildErrorRedirectUriTest {

        @Test
        @DisplayName("Error redirect URI with parameters")
        fun buildErrorRedirectUriWithParameters() {
            val redirectUri = "https://example.com/callback"
            val error = "access_denied"
            val errorDescription = "User denied the request"
            val state = "abc123"

            val result = OAuthValidation.buildErrorRedirectUri(redirectUri, error, errorDescription, state)

            // URLにパラメータが含まれていることを確認
            assertTrue(result.contains("error=access_denied"))
            assertTrue(result.contains("error_description="))
            assertTrue(result.contains("state=abc123"))
            assertTrue(result.startsWith("https://example.com/callback"))
        }
    }

    @Nested
    @DisplayName("buildSuccessRedirectUri")
    inner class BuildSuccessRedirectUriTest {

        @Test
        @DisplayName("Success redirect URI with code and state")
        fun buildSuccessRedirectUriWithCodeAndState() {
            val redirectUri = "https://example.com/callback"
            val code = "authorization_code_123"
            val state = "state_value"

            val result = OAuthValidation.buildSuccessRedirectUri(redirectUri, code, state)

            assertTrue(result.contains("code=authorization_code_123"))
            assertTrue(result.contains("state=state_value"))
            assertTrue(result.startsWith("https://example.com/callback"))
        }
    }

    @Nested
    @DisplayName("validateRedirectUri")
    inner class ValidateRedirectUriTest {

        @Test
        @DisplayName("Redirect URI with trailing slash matches subpath")
        fun redirectUriWithTrailingSlashMatchesSubpath() {
            // 登録URIの末尾に"/"がある場合、任意のサブパスがマッチする
            // ロジック: "https://example.com/callback/" + ".*" → 正規表現として使用
            val clientData = ClientData.PublicClientData(
                clientId = "test-client",
                clientName = "Test Client",
                redirectUri = "https://example.com/callback/"
            )

            // パス以下の任意のURLも許可される
            val result = OAuthValidation.validateRedirectUri(clientData, "https://example.com/callback/success")
            assertTrue(result)
        }

        @Test
        @DisplayName("Redirect URI without trailing slash matches with slash")
        fun redirectUriWithoutTrailingSlashMatchesWithSlash() {
            // 登録URIに"/"がない場合、"/"が追加される
            // ロジック: "https://example.com/callback" + "/" + ".*" → "https://example.com/callback/.*"
            val clientData = ClientData.PublicClientData(
                clientId = "test-client",
                clientName = "Test Client",
                redirectUri = "https://example.com/callback"
            )

            // 末尾に"/"以降がある場合にマッチする
            val result = OAuthValidation.validateRedirectUri(clientData, "https://example.com/callback/code")
            assertTrue(result)
        }

        @Test
        @DisplayName("Regex pattern in redirect URI")
        fun regexPatternInRedirectUri() {
            // ワイルドカードを含むパターン
            // ロジック: "https://\\w+-example.com/callback" + "/" + ".*"
            val clientData = ClientData.PublicClientData(
                clientId = "test-client",
                clientName = "Test Client",
                redirectUri = "https://\\w+-example.com/callback"
            )

            // サブパスを含むURLにマッチする
            val result = OAuthValidation.validateRedirectUri(clientData, "https://hash-example.com/callback/ok")
            assertTrue(result)
        }

        @Test
        @DisplayName("Invalid redirect URI")
        fun invalidRedirectUri() {
            val clientData = ClientData.PublicClientData(
                clientId = "test-client",
                clientName = "Test Client",
                redirectUri = "https://example.com/callback"
            )

            val result = OAuthValidation.validateRedirectUri(clientData, "https://malicious.com/callback/x")
            assertFalse(result)
        }
    }
}
