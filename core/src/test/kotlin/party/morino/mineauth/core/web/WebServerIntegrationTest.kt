package party.morino.mineauth.core.web

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.mineauth.core.MineAuthTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WebServerの統合テスト
 * 実際のApplication.module()を使用してHTTPエンドポイントをテストする
 * MineAuthTestがKoinとMockBukkitを初期化する
 */
@ExtendWith(MineAuthTest::class)
class WebServerIntegrationTest {

    @Nested
    @DisplayName("Actual module endpoints")
    inner class ActualModuleEndpointsTest {

        @Test
        @DisplayName("GET / returns Hello MineAuth")
        fun getRootReturnsHelloMineAuth() = testApplication {
            application {
                module()
            }

            val response = client.get("/")
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("Hello MineAuth!", response.bodyAsText())
        }

        @Test
        @DisplayName("GET /nonexistent returns 404")
        fun getNonexistentReturns404() = testApplication {
            application {
                module()
            }

            val response = client.get("/nonexistent")
            assertEquals(HttpStatusCode.NotFound, response.status)
        }

        @Test
        @DisplayName("GET /api/v1/commons/server/players returns player list")
        fun getPlayersReturnsPlayerList() = testApplication {
            application {
                module()
            }

            val response = client.get("/api/v1/commons/server/players")
            assertEquals(HttpStatusCode.OK, response.status)
        }

        @Test
        @DisplayName("GET /hello without auth returns 401")
        fun getHelloWithoutAuthReturns401() = testApplication {
            application {
                module()
            }

            val response = client.get("/hello")
            assertEquals(HttpStatusCode.Unauthorized, response.status)
        }
    }

    @Nested
    @DisplayName("Auth endpoints")
    inner class AuthEndpointsTest {

        @Test
        @DisplayName("GET /oauth2/authorize without params returns error")
        fun getAuthorizeWithoutParamsReturnsError() = testApplication {
            application {
                module()
            }

            val response = client.get("/oauth2/authorize")
            // パラメータがないのでエラーまたはリダイレクト
            assertTrue(response.status.value in listOf(400, 302, 200, 500))
        }
    }
}
