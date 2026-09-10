package party.morino.mineauth.core.plugin.dispatch

import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.execution.DefaultMethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ParameterResolver
import party.morino.mineauth.core.plugin.route.RouteExecutor
import java.util.concurrent.TimeUnit
import kotlin.reflect.typeOf

/**
 * 実Jettyエンジン上でディスパッチャのルート選択（具体性優先・404/405/OPTIONS）と
 * 本物の実行ハンドラー（通常関数・suspend関数の高速パス）を通した往復を検証する
 */
class PluginEndpointDispatcherJettyTest {

    /** 実際のアドオンと同じく、通常関数とsuspend関数を持つハンドラー */
    private class ShopHandler {
        fun byId(id: String): String = "shop:$id"

        fun mine(): String = "mine"

        suspend fun create(): String {
            // 実際にサスペンドさせて、再開後もレスポンスが返ることを確認する
            delay(1)
            return "created"
        }
    }

    private fun buildDispatcher(): PluginEndpointDispatcher {
        val handler = ShopHandler()
        fun endpoint(
            method: kotlin.reflect.KFunction<*>,
            path: String,
            segments: List<PathSegment>,
            httpMethod: HttpMethodType,
            parameters: List<ParameterInfo> = emptyList(),
            suspending: Boolean = false
        ) = EndpointMetadata(
            method = method,
            handlerInstance = handler,
            path = path,
            pathSegments = segments,
            httpMethod = httpMethod,
            access = EndpointAccess.Public(null),
            parameters = parameters,
            isSuspending = suspending,
            responseType = typeOf<String>(),
            returnsEither = false,
            responseResolvableByCore = true,
            returnsResponse = false
        )

        // あえてパラメータルートを先に登録し、リテラルルートが優先されることを確認する
        val endpoints = listOf(
            endpoint(
                handler::byId, "/shops/{id}",
                listOf(PathSegment.Literal("shops"), PathSegment.Param("id")),
                HttpMethodType.GET,
                parameters = listOf(ParameterInfo.PathParam("id", typeOf<String>()))
            ),
            endpoint(
                handler::mine, "/shops/mine",
                listOf(PathSegment.Literal("shops"), PathSegment.Literal("mine")),
                HttpMethodType.GET
            ),
            endpoint(
                handler::create, "/shops",
                listOf(PathSegment.Literal("shops")),
                HttpMethodType.POST,
                suspending = true
            )
        )
        val executor = RouteExecutor(ParameterResolver(Json), DefaultMethodExecutionHandlerFactory())
        return PluginEndpointDispatcher(executor, AuthenticationHandler()).apply {
            install("sample", NamespaceTable("SamplePlugin", "/api/v1/plugins/sample", endpoints))
        }
    }

    private fun <T> withServer(block: suspend (HttpClient, String) -> T): T {
        val dispatcher = buildDispatcher()
        val server = embeddedServer(Jetty, port = 0) {
            install(ContentNegotiation) { json(Json) }
            routing {
                route("/api/v1/plugins/{namespace}/{path...}") {
                    handle { dispatcher.dispatch(call) }
                }
            }
        }
        server.start(wait = false)
        val client = HttpClient(Java)
        return try {
            runBlocking {
                val port = server.engine.resolvedConnectors().first().port
                block(client, "http://localhost:$port/api/v1/plugins/sample")
            }
        } finally {
            client.close()
            server.stop(0, 0, TimeUnit.SECONDS)
        }
    }

    @Test
    @DisplayName("Literal route wins over parameter route regardless of registration order")
    fun literalRouteWins() = withServer { client, base ->
        assertEquals("mine", client.get("$base/shops/mine").bodyAsText())
        assertEquals("shop:abc", client.get("$base/shops/abc").bodyAsText())
    }

    @Test
    @DisplayName("Suspend handler completes through the same-classloader fast path")
    fun suspendHandlerFastPath() = withServer { client, base ->
        val response = client.post("$base/shops")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("created", response.bodyAsText())
    }

    @Test
    @DisplayName("Unknown path is 404, wrong method is 405 with Allow, OPTIONS is 204 with Allow")
    fun notFoundMethodNotAllowedAndOptions() = withServer { client, base ->
        assertEquals(HttpStatusCode.NotFound, client.get("$base/nothing/here").status)
        // 末尾スラッシュは空セグメントとして404
        assertEquals(HttpStatusCode.NotFound, client.get("$base/shops/mine/").status)

        val wrongMethod = client.put("$base/shops/mine")
        assertEquals(HttpStatusCode.MethodNotAllowed, wrongMethod.status)
        assertEquals("GET", wrongMethod.headers["Allow"])

        val options = client.request("$base/shops") { method = HttpMethod.Options }
        assertEquals(HttpStatusCode.NoContent, options.status)
        assertEquals("POST", options.headers["Allow"])
    }
}
