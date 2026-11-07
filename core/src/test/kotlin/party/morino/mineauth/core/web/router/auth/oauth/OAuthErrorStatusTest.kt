package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * respondOAuthError のステータスコードのテスト
 * RFC 6749 Section 5.2 の既定値を保ったまま、呼び出し側が明示したステータスを返せることを確認する
 */
class OAuthErrorStatusTest : OAuthRouteTestBase() {

    @Test
    @DisplayName("unauthorized_client defaults to 400 and an explicit status overrides it")
    fun explicitStatusOverridesTheDefault() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing {
                get("/default") { call.respondOAuthError(OAuthErrorCode.UNAUTHORIZED_CLIENT) }
                get("/explicit") {
                    call.respondOAuthError(OAuthErrorCode.UNAUTHORIZED_CLIENT, status = HttpStatusCode.Forbidden)
                }
            }
        }

        assertEquals(HttpStatusCode.BadRequest, client.get("/default").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/explicit").status)
    }
}
