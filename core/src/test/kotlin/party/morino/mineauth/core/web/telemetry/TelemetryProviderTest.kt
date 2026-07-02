package party.morino.mineauth.core.web.telemetry

import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.data.OtlpExporterConfig
import party.morino.mineauth.core.file.data.OtlpExporterProtocol

/**
 * TelemetryProviderのメトリクスエクスポーター生成のテスト
 * プロトコル切り替えとHTTPエンドポイントの正規化を検証する
 */
class TelemetryProviderTest {

    @Test
    @DisplayName("GRPC protocol creates OtlpGrpcMetricExporter")
    fun grpcProtocolCreatesGrpcExporter() {
        val config = OtlpExporterConfig(
            protocol = OtlpExporterProtocol.GRPC,
            endpoint = "http://localhost:4317"
        )

        val exporter = TelemetryProvider.createMetricExporter(config)

        assertTrue(exporter is OtlpGrpcMetricExporter)
        exporter.shutdown()
    }

    @Test
    @DisplayName("HTTP protocol creates OtlpHttpMetricExporter")
    fun httpProtocolCreatesHttpExporter() {
        val config = OtlpExporterConfig(
            protocol = OtlpExporterProtocol.HTTP,
            endpoint = "http://localhost:4318"
        )

        val exporter = TelemetryProvider.createMetricExporter(config)

        assertTrue(exporter is OtlpHttpMetricExporter)
        exporter.shutdown()
    }

    @Test
    @DisplayName("HTTP endpoint with signal path suffix is normalized")
    fun httpEndpointWithSignalPathSuffixIsNormalized() {
        // ユーザーが誤って/v1/metricsを含めた場合でも二重に付与されないことを確認
        val config = OtlpExporterConfig(
            protocol = OtlpExporterProtocol.HTTP,
            endpoint = "http://localhost:4318/v1/metrics"
        )

        val exporter = TelemetryProvider.createMetricExporter(config)

        // SDKが/v1/metricsを自動付与するため、二重付与になっていないことを確認
        assertFalse(exporter.toString().contains("/v1/metrics/v1/metrics"))
        exporter.shutdown()
    }
}
