package party.morino.mineauth.core.web.telemetry

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.export.MetricExporter
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.semconv.ServiceAttributes
import org.bukkit.Bukkit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.ObservabilityConfig
import party.morino.mineauth.core.file.data.OtlpExporterConfig
import party.morino.mineauth.core.file.data.OtlpExporterProtocol
import java.net.InetAddress
import java.net.URI
import java.time.Duration

/**
 * OpenTelemetryのプロバイダークラス
 * Jaeger等のバックエンドにトレースを送信するための設定を提供する
 */
object TelemetryProvider : KoinComponent {
    private val plugin: MineAuth by inject()
    private var openTelemetry: OpenTelemetry? = null
    private var sdkTracerProvider: SdkTracerProvider? = null
    private var sdkMeterProvider: SdkMeterProvider? = null

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

            // HTTPプロトコルではSDKがパスを自動付与しないため、シグナル別のフルパスを含める必要がある
            if (exporter.protocol == OtlpExporterProtocol.HTTP &&
                !exporter.endpoint.contains("/v1/traces") &&
                !exporter.endpoint.contains("/v1/metrics")
            ) {
                plugin.logger.warning("HTTP endpoint does not include a signal path (e.g. '/v1/traces') - the SDK does not append it automatically, exports will likely 404")
            }
        }

        // リソース属性（サービス名・バージョン・ホスト・Minecraft情報）を設定
        val resource = Resource.getDefault().merge(Resource.create(buildResourceAttributes(config)))

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
        val sdkBuilder = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))

        // OTLPメトリクスが有効な場合はMeterProviderを構築して配線
        if (config.otlpMetricsEnabled) {
            val meterProviderBuilder = SdkMeterProvider.builder().setResource(resource)

            // 各エクスポーターに対して定期エクスポート用のMetricReaderを登録
            config.exporters.forEach { exporterConfig ->
                val metricReader = PeriodicMetricReader.builder(createMetricExporter(exporterConfig))
                    .setInterval(Duration.ofSeconds(config.metricExportIntervalSeconds))
                    .build()
                meterProviderBuilder.registerMetricReader(metricReader)
            }

            val meterProvider = meterProviderBuilder.build()
            sdkMeterProvider = meterProvider
            sdkBuilder.setMeterProvider(meterProvider)
        }

        val sdk = sdkBuilder.build()

        openTelemetry = sdk
        plugin.logger.info("OpenTelemetry initialized successfully")
        return sdk
    }

    /**
     * リソース属性を構築する
     * サービス情報に加えて、ホスト名やMinecraftサーバーの情報を付与する
     *
     * @param config Observability設定
     * @return リソース属性
     */
    private fun buildResourceAttributes(config: ObservabilityConfig): Attributes {
        val builder = Attributes.builder()
            .put(ServiceAttributes.SERVICE_NAME, config.serviceName)
            .put(TelemetryAttributes.SERVICE_NAMESPACE, config.serviceNamespace)

        // プラグインのメタ情報（バージョン・名前）
        // テスト環境などでプラグインが取得できない場合はスキップ
        try {
            builder.put(ServiceAttributes.SERVICE_VERSION, plugin.pluginMeta.version)
            builder.put(TelemetryAttributes.MINECRAFT_PLUGIN_NAME, plugin.pluginMeta.name)
        } catch (e: Exception) {
            plugin.logger.fine("Could not resolve plugin meta for resource attributes: ${e.message}")
        }

        // ホスト名（解決に失敗した場合は省略）
        try {
            builder.put(TelemetryAttributes.HOST_NAME, InetAddress.getLocalHost().hostName)
        } catch (e: Exception) {
            plugin.logger.fine("Could not resolve host name for resource attributes: ${e.message}")
        }

        // Minecraftサーバー情報（MockBukkit等ではサーバー未初期化の場合があるためガード）
        try {
            builder.put(TelemetryAttributes.MINECRAFT_VERSION, Bukkit.getMinecraftVersion())
            builder.put(TelemetryAttributes.MINECRAFT_SERVER_BRAND, Bukkit.getName())
        } catch (e: Exception) {
            plugin.logger.fine("Could not resolve Minecraft server info for resource attributes: ${e.message}")
        }

        return builder.build()
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
     * メーターを取得する
     *
     * @param instrumentationName インストルメンテーション名
     * @return Meterインスタンス（未初期化の場合はNoOp）
     */
    fun getMeter(instrumentationName: String = "mineauth"): Meter {
        return openTelemetry?.getMeter(instrumentationName)
            ?: OpenTelemetry.noop().getMeter(instrumentationName)
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
                // 注意: OtlpHttpSpanExporterはパスを自動付与しないため、
                // config.endpointにシグナル別のフルパス（例: /v1/traces）を含める必要がある
                val normalizedEndpoint = config.endpoint.removeSuffix("/")

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
     * OTLPメトリクスエクスポーターを作成する
     * createSpanExporterと同様にヘッダー設定をサポートして認証付きバックエンドにも対応
     *
     * @param config エクスポーター設定
     * @return 設定済みのMetricExporter
     */
    internal fun createMetricExporter(config: OtlpExporterConfig): MetricExporter {
        // プロトコルに応じてOTLPエクスポーターを切り替える
        return when (config.protocol) {
            OtlpExporterProtocol.GRPC -> {
                // gRPCエクスポーターを構築
                val builder = OtlpGrpcMetricExporter.builder()
                    .setEndpoint(config.endpoint)
                    .setTimeout(Duration.ofSeconds(30)) // タイムアウトを30秒に設定

                // ヘッダーを設定（認証用など）
                config.headers.forEach { (key, value) ->
                    builder.addHeader(key, value)
                }

                builder.build()
            }
            OtlpExporterProtocol.HTTP -> {
                // HTTPエクスポーターを構築
                // 注意: OtlpHttpMetricExporterはパスを自動付与しないため、
                // config.endpointにシグナル別のフルパス（例: /v1/metrics）を含める必要がある
                val normalizedEndpoint = config.endpoint.removeSuffix("/")

                val builder = OtlpHttpMetricExporter.builder()
                    .setEndpoint(normalizedEndpoint)
                    .setTimeout(Duration.ofSeconds(30)) // タイムアウトを30秒に設定

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
        sdkMeterProvider?.shutdown()
        openTelemetry = null
        sdkTracerProvider = null
        sdkMeterProvider = null
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
