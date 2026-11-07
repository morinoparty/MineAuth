package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.right
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * クライアントクレデンシャル抽出とConfidentialクライアント認証のテスト
 * DBやルーティングに依存しない分岐を純粋関数として確認する
 */
class OAuthClientCredentialsTest {

    /** "username:password" をBasicヘッダーの値にエンコードする */
    private fun basic(userPass: String): String =
        "Basic " + Base64.getEncoder().encodeToString(userPass.toByteArray())

    /** 失敗を期待する結果からエラーコードを取り出す */
    private fun <T> Either<ClientAuthFailure, T>.errorCode(): OAuthErrorCode =
        assertIs<Either.Left<ClientAuthFailure>>(this).value.errorCode

    @Nested
    @DisplayName("Basic header only")
    inner class BasicOnly {

        @Test
        @DisplayName("Uses the username and password as credentials")
        fun usesBasicCredentials() {
            val result = OAuthValidation.extractClientCredentials(basic("client:secret"), Parameters.Empty)
            assertEquals(ClientCredentials("client", "secret").right(), result)
        }

        @Test
        @DisplayName("Treats an empty password as no secret")
        fun emptyPasswordIsNoSecret() {
            val result = OAuthValidation.extractClientCredentials(basic("client:"), Parameters.Empty)
            assertEquals(ClientCredentials("client", null).right(), result)
        }

        @Test
        @DisplayName("Rejects a malformed Basic header as invalid_client")
        fun rejectsMalformedBasicHeader() {
            val malformedHeaders = listOf(
                "Basic",
                "Basic !!!not-base64!!!",
                basic("no-colon"),
                basic(":secret-without-username")
            )

            malformedHeaders.forEach { header ->
                val result = OAuthValidation.extractClientCredentials(header, Parameters.Empty)
                assertEquals(OAuthErrorCode.INVALID_CLIENT, result.errorCode(), header)
            }
        }

        @Test
        @DisplayName("Malformed Basic header wins over valid body credentials")
        fun malformedBasicHeaderWinsOverBody() {
            val body = parametersOf("client_id" to listOf("client"), "client_secret" to listOf("secret"))
            val result = OAuthValidation.extractClientCredentials("Basic !!!", body)
            assertEquals(OAuthErrorCode.INVALID_CLIENT, result.errorCode())
        }
    }

    @Nested
    @DisplayName("Basic header with body parameters")
    inner class BasicWithBody {

        @Test
        @DisplayName("Accepts a matching body client_id")
        fun acceptsMatchingBodyClientId() {
            val result = OAuthValidation.extractClientCredentials(
                basic("client:secret"),
                parametersOf("client_id", "client")
            )
            assertEquals(ClientCredentials("client", "secret").right(), result)
        }

        @Test
        @DisplayName("Rejects a differing body client_id")
        fun rejectsDifferingBodyClientId() {
            val result = OAuthValidation.extractClientCredentials(
                basic("client:secret"),
                parametersOf("client_id", "other")
            )
            assertEquals(OAuthErrorCode.INVALID_REQUEST, result.errorCode())
        }

        @Test
        @DisplayName("Rejects a body client_secret as a second method")
        fun rejectsBodyClientSecret() {
            val result = OAuthValidation.extractClientCredentials(
                basic("client:secret"),
                parametersOf("client_secret", "secret")
            )
            assertEquals(OAuthErrorCode.INVALID_REQUEST, result.errorCode())
        }

        @Test
        @DisplayName("Ignores an empty body client_secret")
        fun ignoresEmptyBodyClientSecret() {
            val result = OAuthValidation.extractClientCredentials(
                basic("client:secret"),
                parametersOf("client_secret", "")
            )
            assertEquals(ClientCredentials("client", "secret").right(), result)
        }
    }

    @Nested
    @DisplayName("Body parameters only")
    inner class BodyOnly {

        @Test
        @DisplayName("Supports client_secret_post")
        fun supportsClientSecretPost() {
            val body = parametersOf("client_id" to listOf("client"), "client_secret" to listOf("secret"))
            val result = OAuthValidation.extractClientCredentials(null, body)
            assertEquals(ClientCredentials("client", "secret").right(), result)
        }

        @Test
        @DisplayName("Supports a public client sending client_id only")
        fun supportsPublicClient() {
            val result = OAuthValidation.extractClientCredentials(null, parametersOf("client_id", "public"))
            assertEquals(ClientCredentials("public", null).right(), result)
        }

        @Test
        @DisplayName("Ignores a non-Basic Authorization scheme")
        fun ignoresOtherScheme() {
            val result = OAuthValidation.extractClientCredentials("Bearer token", parametersOf("client_id", "public"))
            assertEquals(ClientCredentials("public", null).right(), result)
        }

        @Test
        @DisplayName("Rejects a missing or empty client_id as invalid_request")
        fun rejectsMissingClientId() {
            assertEquals(
                OAuthErrorCode.INVALID_REQUEST,
                OAuthValidation.extractClientCredentials(null, Parameters.Empty).errorCode()
            )
            assertEquals(
                OAuthErrorCode.INVALID_REQUEST,
                OAuthValidation.extractClientCredentials(null, parametersOf("client_id", "")).errorCode()
            )
        }

        @Test
        @DisplayName("Rejects a repeated client_id as invalid_request")
        fun rejectsRepeatedClientId() {
            val result = OAuthValidation.extractClientCredentials(null, parametersOf("client_id", listOf("a", "a")))
            assertEquals(OAuthErrorCode.INVALID_REQUEST, result.errorCode())
        }
    }

    @Nested
    @DisplayName("authenticateConfidentialClient")
    inner class AuthenticateConfidentialClient {

        @Test
        @DisplayName("Rejects a missing secret as invalid_client")
        fun rejectsMissingSecret() = runTest {
            val result = OAuthValidation.authenticateConfidentialClient(
                ClientCredentials("client", null),
                ClientIpKey("203.0.113.1")
            )
            assertEquals(OAuthErrorCode.INVALID_CLIENT, result.errorCode())
        }
    }
}
