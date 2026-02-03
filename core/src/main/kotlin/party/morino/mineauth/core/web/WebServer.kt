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
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import io.opentelemetry.instrumentation.ktor.v3_0.KtorServerTelemetry
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
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.openapi.OpenApiRouter.openApiRouter
import party.morino.mineauth.core.web.router.auth.AuthRouter.authRouter
import party.morino.mineauth.core.web.router.common.CommonRouter.commonRouter
import party.morino.mineauth.core.web.router.plugin.PluginRouter.pluginRouter
import party.morino.mineauth.core.plugin.PluginRouteRegistry
import party.morino.mineauth.core.web.telemetry.TelemetryProvider
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
    }

    // Prometheusメトリクスレジストリ
    val appMicrometerRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

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
        registry = appMicrometerRegistry
        // メトリクス収集対象から除外するパス
        distinctNotRegisteredRoutes = false
    }

    install(ContentNegotiation) {
        json(kotlinx.serialization.json.Json {
            // nullフィールドをJSONに出力しない（OpenAPIドキュメント等で必要）
            explicitNulls = false
            // 未知のフィールドを無視
            ignoreUnknownKeys = true
        })
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
                // JWTからクライアントIDを取得してDBで検証
                val clientId = credential.payload.getClaim("client_id").asString()
                val clientResult = OAuthClientRepository.findById(clientId)
                if (clientResult.isLeft()) {
                    return@validate null
                }

                // RFC 7009: トークンが失効済みかチェック
                val tokenId = credential.payload.id
                if (tokenId != null && RevokedTokenRepository.isRevokedBlocking(tokenId)) {
                    return@validate null
                }

                JWTPrincipal(credential.payload)
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
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
                call.respond(appMicrometerRegistry.scrape())
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

        // 外部プラグインから登録されたルートを適用
        val routeRegistry: PluginRouteRegistry = get(PluginRouteRegistry::class.java)
        routeRegistry.applyAll(this)

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
