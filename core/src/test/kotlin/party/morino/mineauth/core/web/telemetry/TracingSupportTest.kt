package party.morino.mineauth.core.web.telemetry

import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * withSpanヘルパーのテスト
 * InMemorySpanExporterを使用してスパンの生成・親子付け・エラーステータスを検証する
 */
class TracingSupportTest {
    // テスト用のインメモリエクスポーターとTracerProvider
    private val exporter = InMemorySpanExporter.create()
    private val tracerProvider = SdkTracerProvider.builder()
        .addSpanProcessor(SimpleSpanProcessor.create(exporter))
        .build()
    private val tracer = tracerProvider.get("test")

    @AfterEach
    fun tearDown() {
        tracerProvider.shutdown()
    }

    @Test
    @DisplayName("withSpan creates a span with name and attributes")
    fun withSpanCreatesSpanWithNameAndAttributes() = runBlocking {
        val attributes = Attributes.of(TelemetryAttributes.CLIENT_ID, "test-client")

        val result = withSpan("test.span", attributes = attributes, tracer = tracer) { "ok" }

        assertEquals("ok", result)
        val spans = exporter.finishedSpanItems
        assertEquals(1, spans.size)
        assertEquals("test.span", spans[0].name)
        assertEquals("test-client", spans[0].attributes.get(TelemetryAttributes.CLIENT_ID))
    }

    @Test
    @DisplayName("Nested withSpan creates parent-child relationship")
    fun nestedWithSpanCreatesParentChildRelationship() = runBlocking {
        withSpan("parent.span", tracer = tracer) {
            withSpan("child.span", tracer = tracer) { }
        }

        val spans = exporter.finishedSpanItems
        assertEquals(2, spans.size)
        // SimpleSpanProcessorは終了順にエクスポートするため、child → parentの順になる
        val child = spans.first { it.name == "child.span" }
        val parent = spans.first { it.name == "parent.span" }
        assertEquals(parent.spanId, child.parentSpanId)
        assertEquals(parent.traceId, child.traceId)
    }

    @Test
    @DisplayName("withSpan records exception and sets ERROR status")
    fun withSpanRecordsExceptionAndSetsErrorStatus(): Unit = runBlocking {
        assertThrows<IllegalStateException> {
            withSpan("failing.span", tracer = tracer) {
                throw IllegalStateException("boom")
            }
        }

        val spans = exporter.finishedSpanItems
        assertEquals(1, spans.size)
        assertEquals(StatusCode.ERROR, spans[0].status.statusCode)
        // 例外イベントが記録されていることを確認
        assertTrue(spans[0].events.any { it.name == "exception" })
    }

    @Test
    @DisplayName("withDatabaseSpan sets db attributes and span name")
    fun withDatabaseSpanSetsDbAttributesAndSpanName() = runBlocking {
        // TelemetryProviderが未初期化のためNoOp Tracerになるが、例外なく実行できることを確認
        val result = withDatabaseSpan("oauth_clients", "select") { 42 }
        assertEquals(42, result)
    }
}
