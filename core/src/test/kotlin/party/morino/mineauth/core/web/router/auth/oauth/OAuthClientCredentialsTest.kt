package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.right
import io.ktor.http.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
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
    }
}
