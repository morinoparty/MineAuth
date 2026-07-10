package party.morino.mineauth.core.web.telemetry

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.routing.*
import kotlinx.coroutines.withContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.semconv.HttpAttributes
import io.opentelemetry.semconv.UrlAttributes
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
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
import kotlin.reflect.typeOf

/**
 * 本番と同じJettyエンジンでhttp.routeの補正を検証する統合テスト
 *
 * testApplicationのテストエンジンではコルーチンのContext伝播が本番と異なり、
 * ハンドラ内のContext.current()がサーバースパンを保持してしまう（＝偽陽性）。
 * このテストは実Jettyサーバーを起動し、実HTTPリクエストでhttp.route属性を検証することで、
 * ディスパッチャのルート補正が本番エンジン上でも機能することを保証する。
 */
class HttpRouteJettyTest {

    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val openTelemetry = OpenTelemetrySdk.builder()
        .setTracerProvider(tracerProvider)
        .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
        .build()

    @AfterEach
    fun tearDown() {
        tracerProvider.shutdown()
    }

    private class DummyHandler {
        fun handle(): String = "ok"
    }

    /** 公開エンドポイント /balance/{player} を1件登録した本物のディスパッチャを構築する */
    private fun buildDispatcher(): PluginEndpointDispatcher {
        val handler = DummyHandler()
        val endpoint = EndpointMetadata(
            method = handler::handle,
            handlerInstance = handler,
            path = "/balance/{player}",
            pathSegments = listOf(PathSegment.Literal("balance"), PathSegment.Param("player")),
            httpMethod = HttpMethodType.GET,
            access = EndpointAccess.Public(null),
            parameters = emptyList(),
            isSuspending = false,
            responseType = typeOf<String>(),
            returnsEither = false
        )
        val executor = RouteExecutor(
            ParameterResolver(Json),
            object : MethodExecutionHandlerFactory {
                override fun createHandler(metadata: EndpointMetadata): MethodExecutionHandler =
                    object : MethodExecutionHandler {
                        override suspend fun execute(
                            metadata: EndpointMetadata,
                            resolvedParams: List<Any?>
                        ): Either<ExecutionError, Any?> = Either.Right("ok")
                    }
            }
        )
        return PluginEndpointDispatcher(executor, AuthenticationHandler()).apply {
            install("vault", NamespaceTable("VaultPlugin", "/api/v1/plugins/vault", listOf(endpoint)))
        }
    }

    private fun serverSpan(): SpanData {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS)
        return exporter.finishedSpanItems.first { it.kind == SpanKind.SERVER }
    }

    @Test
    @DisplayName("Dispatcher refines http.route on the real Jetty engine")
    fun dispatcherRefinesRouteOnJetty() {
        val dispatcher = buildDispatcher()
        val server = embeddedServer(Jetty, port = 0) {
            install(Authentication) { bearer("test-auth") { authenticate { null } } }
            install(KtorServerTelemetry) {
                setOpenTelemetry(openTelemetry)
                attributesExtractor {
                    onEnd {
                        request.call.attributes.getOrNull(OTEL_ROUTE_TEMPLATE)?.let { template ->
                            attributes.put(io.opentelemetry.semconv.UrlAttributes.URL_PATH, template)
                        }
                    }
                }
            }
            installAuthRouteSanitizer()
            routing {
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    route("/api/v1/plugins/{namespace}/{path...}") {
                        handle {
                            // 本番では認証インターセプタ等のスレッド切り替えでハンドラのContext.current()が
                            // サーバースパンを失うことがある。ここでは明示的にrootのContextを現在にして
                            // その状況を決定的に再現する。Context.current()に頼る実装はここで失敗する。
                            withContext(Context.root().asContextElement()) {
                                dispatcher.dispatch(call)
                            }
                        }
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val client = HttpClient(Java)
            val body = runBlocking {
                client.get("http://localhost:$port/api/v1/plugins/vault/balance/_NIKOMARU").bodyAsText()
            }
            client.close()
            assertEquals("ok", body)

            val span = serverSpan()
            // 本番エンジン上でも実エンドポイントのテンプレートに補正されること
            assertEquals("/api/v1/plugins/vault/balance/{player}", span.attributes.get(HttpAttributes.HTTP_ROUTE))
            assertEquals("GET /api/v1/plugins/vault/balance/{player}", span.name)
            // url.pathもテンプレート化され、具体的なプレイヤー名（PII）が含まれないこと
            assertEquals("/api/v1/plugins/vault/balance/{player}", span.attributes.get(UrlAttributes.URL_PATH))
        } finally {
            server.stop(0, 0, TimeUnit.SECONDS)
        }
    }
}
