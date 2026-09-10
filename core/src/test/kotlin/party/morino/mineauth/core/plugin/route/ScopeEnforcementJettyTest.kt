package party.morino.mineauth.core.plugin.route

import com.auth0.jwt.interfaces.Claim
import com.auth0.jwt.interfaces.Payload
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.dispatch.NamespaceTable
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import party.morino.mineauth.core.plugin.execution.DefaultMethodExecutionHandlerFactory
import java.util.concurrent.TimeUnit
import kotlin.reflect.typeOf

/**
 * `@Authenticated`エンドポイントに対する`plugin`スコープ必須の検証（実Jetty）
 *
 * JWTの検証自体はKtorのAuthenticationプラグインの責務なので、ここでは検証済みの
 * JWTPrincipalをbearerプロバイダから注入し、AuthenticationHandlerのスコープ判定だけを確認する
 */
class ScopeEnforcementJettyTest {

    private class Handler {
        fun secret(): String = "secret"
    }

    /** トークン文字列 -> そのトークンが表すscopeクレーム（テスト用の固定マッピング） */
    private val tokens = mapOf(
        "login-only" to "openid profile",
        "with-plugin" to "openid plugin"
    )

    /** 検証済みユーザートークン相当のJWTPrincipalを組み立てる */
    private fun principalFor(scope: String): JWTPrincipal {
        fun claim(value: String?): Claim = mockk<Claim>().also { every { it.asString() } returns value }
        val payload = mockk<Payload>()
        every { payload.getClaim("token_type") } returns claim("token")
        every { payload.getClaim("playerUniqueId") } returns claim("00000000-0000-0000-0000-000000000001")
        every { payload.getClaim("scope") } returns claim(scope)
        every { payload.getClaim("client_id") } returns claim("test-client")
        return JWTPrincipal(payload)
    }

    private fun <T> withServer(block: suspend (HttpClient, String) -> T): T {
        val handler = Handler()
        val endpoint = EndpointMetadata(
            method = handler::secret,
            handlerInstance = handler,
            path = "/secret",
            pathSegments = listOf(PathSegment.Literal("secret")),
            httpMethod = HttpMethodType.GET,
            // permission = null なのでPermissionChecker（Koin）には触れない
            access = EndpointAccess.Authenticated(permission = null, callers = setOf(CallerType.USER)),
            parameters = emptyList(),
            isSuspending = false,
            responseType = typeOf<String>(),
            returnsEither = false,
            responseResolvableByCore = true,
            returnsResponse = false
        )
        val executor = RouteExecutor(ParameterResolver(Json), DefaultMethodExecutionHandlerFactory())
        val dispatcher = PluginEndpointDispatcher(executor, AuthenticationHandler()).apply {
            install("sample", NamespaceTable("SamplePlugin", "/api/v1/plugins/sample", listOf(endpoint)))
        }
        val server = embeddedServer(Jetty, port = 0) {
            install(ContentNegotiation) { json(Json) }
            install(Authentication) {
                bearer("test-auth") {
                    authenticate { credential -> tokens[credential.token]?.let { principalFor(it) } }
                }
            }
            routing {
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    route("/api/v1/plugins/{namespace}/{path...}") {
                        handle { dispatcher.dispatch(call) }
                    }
                }
            }
        }
        server.start(wait = false)
        val client = HttpClient(Java)
        return try {
            runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                block(client, "http://localhost:$port/api/v1/plugins/sample/secret")
            }
        } finally {
            client.close()
            server.stop(0, 0, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("User token without plugin scope is rejected with 403 insufficient_scope")
    fun missingPluginScopeIsRejected() = withServer { client, url ->
        val response = client.get(url) { header(HttpHeaders.Authorization, "Bearer login-only") }
        assertEquals(HttpStatusCode.Forbidden, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"code\":\"insufficient_scope\""), body)
        assertTrue(body.contains("\"required_scope\":\"plugin\""), body)
    }

    @Test
    @DisplayName("User token with plugin scope reaches the handler")
    fun pluginScopeIsAccepted() = withServer { client, url ->
        val response = client.get(url) { header(HttpHeaders.Authorization, "Bearer with-plugin") }
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("secret", response.bodyAsText())
    }

    @Test
    @DisplayName("Missing token on an authenticated endpoint is 401")
    fun missingTokenIsUnauthorized() = withServer { client, url ->
        assertEquals(HttpStatusCode.Unauthorized, client.get(url).status)
    }
}
