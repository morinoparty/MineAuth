package party.morino.mineauth.core.web.router.auth.oauth

import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * クライアントの登録情報をDBから読めない場合のクライアント認証のルートテスト
 * DBの障害は認証情報の推測ではないので、server_errorで応答し、接続元をバックオフに数えないことを確認する
 *
 * oauth_clientsテーブルの名前を変えて、読み込みを実際に失敗させる
 */
class ClientRegistryFailureRouteTest : OAuthRouteTestBase() {
    // クライアント認証のバックオフがブロックを始める失敗回数（猶予の10回と、ブロックを始める11回目）
    private val failuresUntilBlocked = 11

    /** 有効な流量制限を登録する */
    private fun useRateLimits() {
        val limits = OAuthRateLimits(RateLimitConfig(), emptyList(), Logger.getLogger("ClientRegistryFailureRouteTest"))
        loadKoinModules(module { single { limits } })
    }

    /** oauth_clientsテーブルを読めなくする */
    private fun makeRegistryUnreadable() = transaction { exec("ALTER TABLE oauth_clients RENAME TO oauth_clients_unavailable") }

    /** oauth_clientsテーブルを元に戻す */
    private fun restoreRegistry() = transaction { exec("ALTER TABLE oauth_clients_unavailable RENAME TO oauth_clients") }

    private suspend fun ApplicationTestBuilder.post(path: String, client: OAuthClientCreationResult): HttpResponse =
        this.client.submitForm(
            url = path,
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", "not-a-jwt")
                append("token", "not-a-jwt")
                append("client_id", client.client.clientId)
                append("client_secret", client.clientSecret!!)
            }
        )

    private suspend fun assertServerError(response: HttpResponse) {
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("server_error", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    @Test
    @DisplayName("An unreadable client registry answers server_error and does not block the address")
    fun unreadableRegistryIsNotCounted() {
        val logHandler = RecordingLogHandler()
        // OAuthRouteTestBaseがプラグインのロガーとして登録しているロガーの出力を記録する
        val pluginLogger = Logger.getLogger("OAuthRouteTest")
        pluginLogger.addHandler(logHandler)
        var secret = ""
        try {
            oauthTestApplication {
                useRateLimits()
                val resourceServer = registerResourceServer()
                secret = resourceServer.clientSecret!!

                makeRegistryUnreadable()
                // バックオフの猶予を超えても、invalid_clientや429にはならない
                repeat(failuresUntilBlocked + 1) { assertServerError(post("/oauth2/token", resourceServer)) }
                assertServerError(post("/oauth2/revoke", resourceServer))
                assertServerError(post("/oauth2/introspect", resourceServer))
                restoreRegistry()

                // 障害の間のリクエストは数えられていないので、復旧後すぐに認証できる
                assertEquals(HttpStatusCode.OK, post("/oauth2/introspect", resourceServer).status)
            }

            // 管理者が障害に気づけるようSEVEREで記録し、シークレットは記録しない
            val severes = logHandler.severes
            assertEquals(failuresUntilBlocked + 3, severes.size)
            assertTrue(severes.all { it.contains("client authentication") })
            assertTrue(severes.none { it.contains(secret) })
        } finally {
            pluginLogger.removeHandler(logHandler)
        }
    }
}
