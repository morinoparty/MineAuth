package party.morino.mineauth.core.web.telemetry

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.engine.embeddedServer
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.http.CacheControl
import party.morino.mineauth.api.http.ConditionalRequest
import party.morino.mineauth.api.http.Response
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.dispatch.NamespaceTable
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import party.morino.mineauth.core.plugin.execution.ExecutionError
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandler
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ParameterResolver
import party.morino.mineauth.core.plugin.route.RouteExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlin.reflect.typeOf

/**
 * issue #375: Responseラッパーによるキャッシュヘッダー・条件付き304の実Jetty統合テスト
 */
class ResponseCachingJettyTest {

    @Serializable
    data class Payload(val value: String)

    private val etag = "\"payload-v1\""

    /**
     * 条件付きGETハンドラーを模したディスパッチャを構築する
     * ETagを持つResponse.Okを返し、If-None-Match一致時はハンドラー自身がNotModifiedを返す（Tier-B）
     */
    private fun buildDispatcher(computed: MutableList<String>): PluginEndpointDispatcher {
        val endpoint = EndpointMetadata(
            method = Dummy::handle,
            handlerInstance = Dummy(),
            path = "/data",
            pathSegments = listOf(PathSegment.Literal("data")),
            httpMethod = HttpMethodType.GET,
            access = EndpointAccess.Public(null),
            parameters = listOf(ParameterInfo.Conditional),
            isSuspending = false,
            responseType = typeOf<Payload>(),
            returnsEither = false,
            responseResolvableByCore = true,
            returnsResponse = true
        )
        val executor = RouteExecutor(
            ParameterResolver(Json),
            object : MethodExecutionHandlerFactory {
                override fun createHandler(metadata: EndpointMetadata): MethodExecutionHandler =
                    object : MethodExecutionHandler {
                        override suspend fun execute(
                            metadata: EndpointMetadata,
                            resolvedParams: List<Any?>
                        ): Either<ExecutionError, Any?> {
                            // 注入されたConditionalRequestで安価にETagを比較（Tier-B: ボディ生成前に短絡）
                            val cond = resolvedParams.filterIsInstance<ConditionalRequest>().first()
                            if (cond.isNoneMatch(etag)) {
                                return Either.Right(Response.notModified(etag, CacheControl.maxAge(60)))
                            }
                            // 高価なボディ生成（テストでは計測用に記録）
                            computed.add("built")
                            return Either.Right(
                                Response.of(Payload("hello"), etag = etag, cacheControl = CacheControl.maxAge(60))
                            )
                        }
                    }
            }
        )
        return PluginEndpointDispatcher(executor, AuthenticationHandler()).apply {
            install("sample", NamespaceTable("SamplePlugin", "/api/v1/plugins/sample", listOf(endpoint)))
        }
    }

    private class Dummy {
        fun handle(): String = "unused"
    }

    private fun <T> withServer(dispatcher: PluginEndpointDispatcher, block: (Int) -> T): T {
        val server = embeddedServer(Jetty, port = 0) {
            install(Authentication) { bearer("test-auth") { authenticate { null } } }
            // 本番同様にContentNegotiationを入れる（core直列化パスで使用）
            install(ContentNegotiation) { json(Json) }
            routing {
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    route("/api/v1/plugins/{namespace}/{path...}") {
                        handle { dispatcher.dispatch(call) }
                    }
                }
            }
        }
        server.start(wait = false)
        return try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(0, 0, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("First GET returns 200 with ETag and Cache-Control")
    fun firstGetHasCacheHeaders() {
        val computed = mutableListOf<String>()
        withServer(buildDispatcher(computed)) { port ->
            val client = HttpClient(Java)
            val response = runBlocking { client.get("http://localhost:$port/api/v1/plugins/sample/data") }
            val body = runBlocking { response.bodyAsText() }
            client.close()

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(etag, response.headers["ETag"])
            assertEquals("private, max-age=60", response.headers["Cache-Control"])
            assertEquals("""{"value":"hello"}""", body)
            assertEquals(1, computed.size) // ボディが生成された
        }
    }

    @Test
    @DisplayName("Matching If-None-Match yields 304 and skips body computation")
    fun conditionalGetReturns304() {
        val computed = mutableListOf<String>()
        withServer(buildDispatcher(computed)) { port ->
            val client = HttpClient(Java)
            val response = runBlocking {
                client.get("http://localhost:$port/api/v1/plugins/sample/data") {
                    header("If-None-Match", etag)
                }
            }
            val body = runBlocking { response.bodyAsText() }
            client.close()

            assertEquals(HttpStatusCode.NotModified, response.status)
            assertEquals(etag, response.headers["ETag"])
            assertTrue(body.isEmpty()) // ボディなし
            assertEquals(0, computed.size) // Tier-B: 高価なボディ生成はスキップされた
        }
    }
}
