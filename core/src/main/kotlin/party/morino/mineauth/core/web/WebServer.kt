package party.morino.mineauth.core.web

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.jetty.jakarta.*
import io.ktor.server.metrics.micrometer.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.micrometer.core.instrument.composite.CompositeMeterRegistry
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
import io.opentelemetry.instrumentation.micrometer.v1_5.OpenTelemetryMeterRegistry
import org.slf4j.event.Level
import io.ktor.server.velocity.*
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.FileResourceLoader
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.get
import org.koin.java.KoinJavaComponent.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.WebServerConfigData
import party.morino.mineauth.core.utils.PlayerUtils.toOfflinePlayer
import party.morino.mineauth.core.utils.PlayerUtils.toUUID
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository
import party.morino.mineauth.core.openapi.OpenApiRouter.openApiRouter
import party.morino.mineauth.core.web.router.auth.AuthRouter.authRouter
import party.morino.mineauth.core.web.router.common.CommonRouter.commonRouter
import party.morino.mineauth.core.web.router.plugin.PluginRouter.pluginRouter
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import party.morino.mineauth.core.web.telemetry.MinecraftMetrics
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.web.telemetry.TelemetryProvider
import party.morino.mineauth.core.web.telemetry.installAuthRouteSanitizer
import party.morino.mineauth.core.web.telemetry.withSpan
import java.security.KeyStore
import java.util.concurrent.TimeUnit

object WebServer : KoinComponent {
    private val plugin: MineAuth by inject()
    var originalServer: EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration>? = null
    fun settingServer() {
        val webServerConfigData: WebServerConfigData = get()
        plugin.logger.info("Setting up web server")
        val environment = applicationEnvironment {


        }
        originalServer = embeddedServer(Jetty, environment = environment, configure = {
            connector {
                port = webServerConfigData.port
            }
            if (webServerConfigData.ssl != null) {
                val keyStoreFile = plugin.dataFolder.resolve("keystore.jks")
                val keystore = KeyStore.getInstance(keyStoreFile, webServerConfigData.ssl.keyStorePassword.toCharArray())
                sslConnector(keyStore = keystore, keyAlias = webServerConfigData.ssl.keyAlias, keyStorePassword = { webServerConfigData.ssl.keyStorePassword.toCharArray() }, privateKeyPassword = { webServerConfigData.ssl.privateKeyPassword.toCharArray() }) {
                    port = webServerConfigData.ssl.sslPort
                    keyStorePath = keyStoreFile
                }
            }
        }, Application::module)
    }

    fun startServer() {
        originalServer?.start(wait = false)
    }

    fun stopServer() {
        originalServer?.stop(0, 0, TimeUnit.SECONDS)
        // OpenTelemetryのリソースを解放
        TelemetryProvider.shutdown()
    }
}

internal fun Application.module() {
    val plugin: MineAuth by inject(MineAuth::class.java)
    val jwtConfigData: JWTConfigData = get(JWTConfigData::class.java)
    val mineAuthConfig: MineAuthConfig = get(MineAuthConfig::class.java)
    val observabilityConfig = mineAuthConfig.observability

    // OpenTelemetryトレーシングを初期化
    val openTelemetry = TelemetryProvider.initialize(observabilityConfig)

    // トレーシングが有効な場合、KtorServerTelemetryをインストール
    // KtorServerTelemetryは他のロギング/テレメトリプラグインより先にインストールする必要がある
    if (observabilityConfig.enabled) {
        install(KtorServerTelemetry) {
            setOpenTelemetry(openTelemetry)
        }
        // KtorServerTelemetryが設定するhttp.routeから認証セレクタ由来のノイズ
        // （例: "(authenticate user-oauth-token, service-oauth-token)"）を除去する
        installAuthRouteSanitizer()
    }

    // Prometheusメトリクスレジストリ（/metricsエンドポイント用）
    val prometheusRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    // 複合レジストリ: 1回の登録でPrometheusとOTLPの両方にメトリクスを出す
    val meterRegistry = CompositeMeterRegistry().apply {
        add(prometheusRegistry)
        // OTLPメトリクスが有効な場合はMicrometer→OTelブリッジを追加
        if (observabilityConfig.enabled && observabilityConfig.otlpMetricsEnabled) {
            add(OpenTelemetryMeterRegistry.create(openTelemetry))
        }
    }

    // reload時にレジストリをクローズしてメーターのリークを防ぐ
    monitor.subscribe(ApplicationStopped) {
        meterRegistry.close()
    }

    // HTTPリクエスト/レスポンスのロギング
    install(CallLogging) {
        level = Level.INFO
        // リクエスト情報をログに出力
        format { call ->
            val status = call.response.status()
            val httpMethod = call.request.httpMethod.value
            val path = call.request.path()
            val duration = call.processingTimeMillis()
            "HTTP $httpMethod $path - $status [${duration}ms]"
        }
        // ヘルスチェックやアセット、メトリクスへのリクエストは除外
        filter { call ->
            !call.request.path().startsWith("/assets") &&
                call.request.path() != "/health" &&
                call.request.path() != "/metrics"
        }
    }

    // Micrometerメトリクス
    install(MicrometerMetrics) {
        registry = meterRegistry
        // メトリクス収集対象から除外するパス
        distinctNotRegisteredRoutes = false
    }

    // Minecraftサーバーのメトリクス（プレイヤー数・TPS・MSPTなど）を登録
    // テスト環境などBukkitサーバーが未初期化の場合はスキップ
    if (observabilityConfig.metricsEnabled) {
        runCatching { MinecraftMetrics().bindTo(meterRegistry) }
            .onFailure { plugin.logger.warning("Could not bind Minecraft metrics: ${it.message}") }
    }

    install(ContentNegotiation) {
        // プラグインエンドポイントのボディ解析と同一のJson設定を共有する（Koinシングルトン）
        json(get<kotlinx.serialization.json.Json>(kotlinx.serialization.json.Json::class.java))
    }

    install(Velocity) {
        setProperty(RuntimeConstants.RESOURCE_LOADERS, "file")
        setProperty("resource.loader.file.class", FileResourceLoader::class.java.name)
        setProperty("resource.loader.file.path", plugin.dataFolder.resolve("templates").absolutePath)
    }
    val jwkProvider = JwkProviderBuilder(jwtConfigData.issuer).cached(10, 24, TimeUnit.HOURS).rateLimited(10, 1, TimeUnit.MINUTES).build()
    install(Authentication) {
        jwt(JwtCompleteCode.USER_TOKEN.code) {
            realm = jwtConfigData.realm
            verifier(jwkProvider, jwtConfigData.issuer) {
                acceptLeeway(3)
            }

            validate { credential ->
                // JWT検証の過程をスパンとして記録する（トークン本体は記録しない）
                withSpan("jwt.validate.user_token") { span ->
                    // セキュリティ: アクセストークンのみを受け付ける
                    // （リフレッシュトークンがアクセストークンとして使われるのを防ぐ）
                    val tokenType = credential.payload.getClaim("token_type").asString()
                    if (tokenType != "token") {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "wrong_token_type")
                        return@withSpan null
                    }

                    // JWTからクライアントIDを取得してDBで検証
                    val clientId = credential.payload.getClaim("client_id").asString()
                    clientId?.let { span.setAttribute(TelemetryAttributes.CLIENT_ID, it) }
                    val clientResult = OAuthClientRepository.findById(clientId)
                    if (clientResult.isLeft()) {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "client_not_found")
                        return@withSpan null
                    }

                    // RFC 7009: トークンが失効済みかチェック
                    val tokenId = credential.payload.id
                    if (tokenId != null && RevokedTokenRepository.isRevokedBlocking(tokenId)) {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "revoked")
                        return@withSpan null
                    }

                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, "valid")
                    JWTPrincipal(credential.payload)
                }
            }
            challenge { _, _ ->
                // ドキュメント記載のエラーエンベロープ（error/code）に合わせてJSONで応答する
                call.respond(
                    HttpStatusCode.Unauthorized,
                    party.morino.mineauth.core.plugin.route.ErrorResponse(
                        "Token is not valid or has expired", code = "invalid_token"
                    )
                )
            }
        }

        // サービスアカウントトークンのJWT検証
        jwt(JwtCompleteCode.SERVICE_TOKEN.code) {
            realm = jwtConfigData.realm
            verifier(jwkProvider, jwtConfigData.issuer) {
                acceptLeeway(3)
            }

            validate { credential ->
                // JWT検証の過程をスパンとして記録する（トークン本体は記録しない）
                withSpan("jwt.validate.service_token") { span ->
                    // token_typeがservice_tokenであることを確認
                    val tokenType = credential.payload.getClaim("token_type").asString()
                    if (tokenType != "service_token") {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "wrong_token_type")
                        return@withSpan null
                    }

                    // accountIdでアカウントの存在確認
                    val accountId = credential.payload.getClaim("account_id").asString()
                    if (accountId == null) {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "account_id_missing")
                        return@withSpan null
                    }
                    val accountResult = AccountRepository.findById(accountId)
                    if (accountResult.isLeft()) {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "account_not_found")
                        return@withSpan null
                    }

                    // トークンIDで失効チェック
                    val tokenId = credential.payload.id
                    if (tokenId != null && !ServiceAccountTokenRepository.isTokenValidBlocking(tokenId)) {
                        span.setAttribute(TelemetryAttributes.AUTH_RESULT, "revoked")
                        return@withSpan null
                    }

                    // 最終使用日時を更新（トークン監査用）
                    if (tokenId != null) {
                        ServiceAccountTokenRepository.updateLastUsedAtBlocking(tokenId)
                    }

                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, "valid")
                    JWTPrincipal(credential.payload)
                }
            }
            challenge { _, _ ->
                // ドキュメント記載のエラーエンベロープ（error/code）に合わせてJSONで応答する
                call.respond(
                    HttpStatusCode.Unauthorized,
                    party.morino.mineauth.core.plugin.route.ErrorResponse(
                        "Service token is not valid or has expired", code = "invalid_token"
                    )
                )
            }
        }
    }

    routing {
        route("/") {
            get {
                call.respondText("Hello MineAuth!")
            }
        }

        // Prometheusメトリクスエンドポイント（metricsEnabledが有効な場合のみ）
        if (observabilityConfig.metricsEnabled) {
            get("/metrics") {
                call.respond(prometheusRegistry.scrape())
            }
        }

        // ヘルスチェックエンドポイント（healthEnabledが有効な場合のみ）
        // 本番環境ではロードバランサーからのみアクセス可能にすることを推奨
        if (observabilityConfig.healthEnabled) {
            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }
        }

        staticFiles("assets", plugin.dataFolder.resolve("assets"))

        // OpenAPI/Scalar ドキュメントエンドポイント
        openApiRouter()

        authRouter()
        route("/api/v1/commons") {
            commonRouter()
        }
        route("/api/v1/plugins") {
            pluginRouter()
        }

        // 外部プラグインのエンドポイントをライブディスパッチャで処理する
        // Ktorルートは1つの静的なサブツリーのみで、実際のマッチングはディスパッチャが行う。
        // これによりWebサーバー起動後の登録・登録解除が即座に反映される。
        // 認証はOptional: 公開エンドポイントも同一パスで扱うため、認証の要否はディスパッチャが判断する
        val dispatcher: PluginEndpointDispatcher = get(PluginEndpointDispatcher::class.java)
        authenticate(
            JwtCompleteCode.USER_TOKEN.code,
            JwtCompleteCode.SERVICE_TOKEN.code,
            strategy = AuthenticationStrategy.Optional
        ) {
            route("/api/v1/plugins/{namespace}/{path...}") {
                handle {
                    dispatcher.dispatch(call)
                }
            }
        }

        authenticate("user-oauth-token") {
            get("/hello") {
                val principal = call.principal<JWTPrincipal>()
                val uuid = principal!!.payload.getClaim("playerUniqueId").asString()
                val expiresAt = principal.expiresAt?.time?.minus(System.currentTimeMillis())
                call.respondText("Hello, ${
                    uuid.toUUID().toOfflinePlayer().name
                }! Token is expired at $expiresAt ms.")
            }
        }
    }
}
