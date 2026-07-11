package party.morino.mineauth.core.web.telemetry

import arrow.core.Either
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.testing.testApplication
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
 * http.route（HTTPサーバースパン名）の補正を実リクエストで検証する統合テスト
 *
 * KtorServerTelemetryを実際にインストールし、InMemorySpanExporterに出力されたスパン名・属性を検証する。
 * これにより以下を一度に確認する:
 * - ハンドラ内のContext.current()がサーバースパンのContextを保持していること（伝播）
 * - HttpServerRouteSourceの優先度によりルートが上書きされること
 * - 認証セレクタ由来のノイズが除去されること
 * - PluginEndpointDispatcherが本番コードでルート補正・属性付与を行うこと
 */
class HttpRouteSpanNameTest {

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

    /** SERVERスパンを1件取得する（子スパンはINTERNAL/CLIENTのため除外される） */
    private fun serverSpan(): SpanData {
        tracerProvider.forceFlush().join(5, TimeUnit.SECONDS)
        return exporter.finishedSpanItems.first { it.kind == SpanKind.SERVER }
    }

    /** テスト用のダミーハンドラ（メソッド参照のためだけに存在する） */
    private class DummyHandler {
        fun handle(): String = "ok"
    }

    /**
     * 本物のPluginEndpointDispatcherを構築する
     * メソッド実行はフェイクのファクトリで差し替え、テレメトリの配線だけを検証する
     */
    private fun buildDispatcher(): PluginEndpointDispatcher {
        val handler = DummyHandler()
        val endpoint = EndpointMetadata(
            method = handler::handle,
            handlerInstance = handler,
            path = "/shops/{id}",
            pathSegments = listOf(PathSegment.Literal("shops"), PathSegment.Param("id")),
            httpMethod = HttpMethodType.GET,
            access = EndpointAccess.Public(null),
            parameters = emptyList(),
            isSuspending = false,
            responseType = typeOf<String>(),
            returnsEither = false,
            responseResolvableByCore = true,
            returnsResponse = false
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

    @Test
    @DisplayName("Dispatcher overrides span name and enriches attributes for the matched endpoint")
    fun dispatcherOverridesSpanNameAndEnrichesAttributes() = testApplication {
        val dispatcher = buildDispatcher()
        application {
            install(Authentication) { bearer("test-auth") { authenticate { null } } }
            install(KtorServerTelemetry) { setOpenTelemetry(openTelemetry) }
            installAuthRouteSanitizer()
            routing {
                // WebServerと同一構成: 認証で包んだ単一のキャッチオールをディスパッチャに委譲する
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    route("/api/v1/plugins/{namespace}/{path...}") {
                        handle { dispatcher.dispatch(call) }
                    }
                }
            }
        }

        val response = client.get("/api/v1/plugins/vault/shops/123")
        assertEquals(HttpStatusCode.OK, response.status)

        val span = serverSpan()
        // 実エンドポイントのテンプレートに補正され、認証セレクタが消えていること
        assertEquals("GET /api/v1/plugins/vault/shops/{id}", span.name)
        assertFalse(span.name.contains("authenticate"), "span name must not contain the authenticate selector")
        // ディスパッチャがサーバースパンに識別属性を付与していること
        assertEquals("vault", span.attributes.get(TelemetryAttributes.PLUGIN_NAMESPACE))
        assertEquals("VaultPlugin", span.attributes.get(TelemetryAttributes.PLUGIN_OWNER))
        assertEquals("/api/v1/plugins/vault/shops/{id}", span.attributes.get(TelemetryAttributes.ROUTE_TEMPLATE))
        assertEquals("public", span.attributes.get(TelemetryAttributes.ENDPOINT_ACCESS))
        // 認証ヘッダー無しの公開エンドポイントなのでanonymous
        assertEquals("anonymous", span.attributes.get(TelemetryAttributes.CALLER_TYPE))
    }

    @Test
    @DisplayName("Auth route sanitizer removes the authenticate selector from span name")
    fun authRouteSanitizerRemovesSelector() = testApplication {
        application {
            install(Authentication) { bearer("test-auth") { authenticate { null } } }
            install(KtorServerTelemetry) { setOpenTelemetry(openTelemetry) }
            installAuthRouteSanitizer()
            routing {
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    get("/hello") {
                        call.respondText("hi")
                    }
                }
            }
        }

        val response = client.get("/hello")
        assertEquals(HttpStatusCode.OK, response.status)

        val span = serverSpan()
        // サニタイザにより認証セレクタが除去され、素直なルートになること
        assertEquals("GET /hello", span.name)
        assertFalse(span.name.contains("authenticate"), "span name must not contain the authenticate selector")
    }

    @Test
    @DisplayName("Without sanitizer the authenticate selector pollutes the span name")
    fun withoutSanitizerSelectorPollutesSpanName() = testApplication {
        application {
            install(Authentication) { bearer("test-auth") { authenticate { null } } }
            install(KtorServerTelemetry) { setOpenTelemetry(openTelemetry) }
            // あえてサニタイザを入れない: バグ（汚染）が再現することを固定する
            routing {
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    get("/hello") {
                        call.respondText("hi")
                    }
                }
            }
        }

        val response = client.get("/hello")
        assertEquals(HttpStatusCode.OK, response.status)

        // サニタイザが無い場合、スパン名に認証セレクタが混入することを確認する
        val span = serverSpan()
        assertTrue(span.name.contains("authenticate")) {
            "expected polluted span name to contain the authenticate selector but was: ${span.name}"
        }
    }
}
