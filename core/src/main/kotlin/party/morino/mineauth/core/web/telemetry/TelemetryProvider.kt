package party.morino.mineauth.core.web.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.semconv.ServiceAttributes
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.ObservabilityConfig
import party.morino.mineauth.core.file.data.OtlpExporterConfig
import party.morino.mineauth.core.file.data.OtlpExporterProtocol
import java.net.URI

/**
 * OpenTelemetryのプロバイダークラス
 * Jaeger等のバックエンドにトレースを送信するための設定を提供する
 */
object TelemetryProvider : KoinComponent {
    private val plugin: MineAuth by inject()
    private var openTelemetry: OpenTelemetry? = null
    private var sdkTracerProvider: SdkTracerProvider? = null

    /**
     * OpenTelemetryを初期化する
     * 設定でenabledがtrueの場合のみOTLPエクスポーターを設定
     * 既に初期化済みの場合は一度シャットダウンしてから再初期化する
     *
     * @param config Observability設定
     * @return 初期化されたOpenTelemetryインスタンス
     */
    @Synchronized
    fun initialize(config: ObservabilityConfig): OpenTelemetry {
        // 既に初期化済みの場合は一度シャットダウンして再初期化
        if (openTelemetry != null) {
            plugin.logger.info("Reinitializing OpenTelemetry with new configuration")
            shutdownInternal()
        }

        if (!config.enabled) {
            // トレーシングが無効の場合はNoOpを返す
            plugin.logger.info("OpenTelemetry tracing is disabled")
            val noop = OpenTelemetry.noop()
            openTelemetry = noop
            return noop
        }

        // エクスポーターが設定されていない場合はNoOpを返す
        if (config.exporters.isEmpty()) {
            plugin.logger.info("No OTLP exporters configured, tracing disabled")
            val noop = OpenTelemetry.noop()
            openTelemetry = noop
            return noop
        }

        // セキュリティ: エンドポイントのホスト名のみをログに出力（資格情報の漏洩防止）
        config.exporters.forEach { exporter ->
            val endpointHost = try {
                URI(exporter.endpoint).host ?: "unknown"
            } catch (e: Exception) {
                "invalid-endpoint"
            }
            plugin.logger.info("Initializing OpenTelemetry ${exporter.protocol} exporter for: $endpointHost")

            // HTTPプロトコルで/v1/tracesが含まれている場合は警告
            if (exporter.protocol == OtlpExporterProtocol.HTTP && exporter.endpoint.contains("/v1/traces")) {
                plugin.logger.warning("HTTP endpoint contains '/v1/traces' suffix - this will be automatically removed (SDK appends it)")
            }
        }

        // リソース属性（サービス名など）を設定
        val resource = Resource.getDefault()
            .merge(
                Resource.create(
                    Attributes.of(
                        ServiceAttributes.SERVICE_NAME, config.serviceName
                    )
                )
            )

        // セキュリティ: UUID等の機密情報をマスクするSpanProcessor
        val sanitizingProcessor = SanitizingSpanProcessor()

        // TracerProviderを作成
        val tracerProviderBuilder = SdkTracerProvider.builder()
            .addSpanProcessor(sanitizingProcessor)
            .setResource(resource)

        // 各エクスポーターを追加（複数バックエンドへの送信をサポート）
        config.exporters.forEach { exporterConfig ->
            val spanExporter = createSpanExporter(exporterConfig)
            tracerProviderBuilder.addSpanProcessor(BatchSpanProcessor.builder(spanExporter).build())
        }

        val tracerProvider = tracerProviderBuilder.build()

        sdkTracerProvider = tracerProvider

        // OpenTelemetry SDKを作成
        val sdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()

        openTelemetry = sdk
        plugin.logger.info("OpenTelemetry initialized successfully")
        return sdk
    }

    /**
     * トレーサーを取得する
     *
     * @param instrumentationName インストルメンテーション名
     * @return Tracerインスタンス
     */
    fun getTracer(instrumentationName: String = "mineauth"): Tracer {
        return openTelemetry?.getTracer(instrumentationName)
            ?: OpenTelemetry.noop().getTracer(instrumentationName)
    }

    /**
     * OpenTelemetryインスタンスを取得する
     *
     * @return OpenTelemetryインスタンス（未初期化の場合はNoOp）
     */
    fun get(): OpenTelemetry {
        return openTelemetry ?: OpenTelemetry.noop()
    }

    /**
     * OTLPエクスポーターを作成する
     * ヘッダー設定をサポートして認証付きバックエンドにも対応
     *
     * @param config エクスポーター設定
     * @return 設定済みのSpanExporter
     */
    private fun createSpanExporter(config: OtlpExporterConfig): SpanExporter {
        // プロトコルに応じてOTLPエクスポーターを切り替える
        return when (config.protocol) {
            OtlpExporterProtocol.GRPC -> {
                // gRPCエクスポーターを構築
                val builder = OtlpGrpcSpanExporter.builder()
                    .setEndpoint(config.endpoint)
                    .setTimeout(java.time.Duration.ofSeconds(30)) // タイムアウトを30秒に設定

                // ヘッダーを設定（認証用など）
                config.headers.forEach { (key, value) ->
                    builder.addHeader(key, value)
                }

                builder.build()
            }
            OtlpExporterProtocol.HTTP -> {
                // HTTPエクスポーターを構築
                // 注意: OtlpHttpSpanExporterは自動的に/v1/tracesを追加する
                // ユーザーが誤って/v1/tracesを含めた場合は除去する
                val normalizedEndpoint = config.endpoint
                    .removeSuffix("/v1/traces")
                    .removeSuffix("/")

                val builder = OtlpHttpSpanExporter.builder()
                    .setEndpoint(normalizedEndpoint)
                    .setTimeout(java.time.Duration.ofSeconds(30)) // タイムアウトを30秒に設定

                // ヘッダーを設定（認証用など）
                config.headers.forEach { (key, value) ->
                    builder.addHeader(key, value)
                }

                builder.build()
            }
        }
    }

    /**
     * シャットダウン処理
     * サーバー停止時に呼び出してリソースを解放する
     */
    @Synchronized
    fun shutdown() {
        shutdownInternal()
        plugin.logger.info("OpenTelemetry shutdown completed")
    }

    /**
     * 内部シャットダウン処理
     * ログ出力なしでリソースを解放する（再初期化時に使用）
     */
    private fun shutdownInternal() {
        sdkTracerProvider?.shutdown()
        openTelemetry = null
        sdkTracerProvider = null
    }
}

/**
 * セキュリティ: スパン名から機密情報をサニタイズするSpanProcessor
 * UUID形式の値をマスクして、プレイヤー情報の漏洩を防止する
 *
 * 注意: スパン属性のマスキングはOpenTelemetry SDKの制約により
 * ReadWriteSpanで直接変更できないため、スパン名のみを対象とする
 */
private class SanitizingSpanProcessor : SpanProcessor {
    companion object {
        // UUID形式の正規表現パターン
        private val UUID_PATTERN = Regex(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        )
        private const val MASKED_UUID = "[UUID-MASKED]"
    }

    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        // スパン名からUUIDをマスク
        val sanitizedName = sanitizeString(span.name)
        if (sanitizedName != span.name) {
            span.updateName(sanitizedName)
        }
    }

    override fun isStartRequired(): Boolean = true

    override fun onEnd(span: ReadableSpan) {
        // onEndでは読み取り専用なので変更不可
    }

    override fun isEndRequired(): Boolean = false

    /**
     * 文字列からUUIDをマスクする
     */
    private fun sanitizeString(value: String): String {
        return UUID_PATTERN.replace(value, MASKED_UUID)
    }
}
