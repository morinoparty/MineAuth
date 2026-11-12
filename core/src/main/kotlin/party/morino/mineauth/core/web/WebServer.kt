package party.morino.mineauth.core.web

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
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.get
import org.koin.java.KoinJavaComponent.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.WebServerConfigData
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.utils.PlayerUtils.toOfflinePlayer
import party.morino.mineauth.core.utils.PlayerUtils.toUUID
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository
import party.morino.mineauth.core.openapi.OpenApiRouter.openApiRouter
import party.morino.mineauth.core.web.router.auth.AuthRouter.authRouter
import party.morino.mineauth.core.web.router.auth.oauth.TokenStateValidator
import party.morino.mineauth.core.web.router.common.CommonRouter.commonRouter
import party.morino.mineauth.core.web.router.plugin.PluginRouter.pluginRouter
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import party.morino.mineauth.core.web.telemetry.MinecraftMetrics
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.web.telemetry.TelemetryProvider
import party.morino.mineauth.core.web.telemetry.installAuthRouteSanitizer
import party.morino.mineauth.core.web.telemetry.redactUrlAttributes
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
            // url.pathには具体パス（例: /api/v1/plugins/vault/balance/_NIKOMARU）のPIIが、
            // url.queryにはid_token_hintやstateの値がそのまま入るため、テンプレート化と値の伏せ字で取り除く
            redactUrlAttributes()
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

    // HTTPリクエスト/レスポンスのロギング（流量制限の429などを除外する設定はAccessLog.ktを参照）
    install(CallLogging) {
        configureAccessLog()
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

    install(Authentication) {
        jwt(JwtCompleteCode.USER_TOKEN.code) {
            realm = jwtConfigData.realm
            // ローカルの公開鍵で署名を検証し、自サーバーとtrustedIssuersのissを受け付ける
            verifier(JwtProvider.bearerVerifier)

            validate { credential ->
                // JWT検証の過程をスパンとして記録する（トークン本体は記録しない）
                withSpan("jwt.validate.user_token") { span ->
                    // 想定外の例外はchallengeを素通りしてしまうので、無効なトークンとして拒否する
                    BearerChallenge.failClosed(span, plugin.logger) {
                        val payload = credential.payload
                        payload.getClaim("client_id").asString()?.let { span.setAttribute(TelemetryAttributes.CLIENT_ID, it) }

                        // セキュリティ: アクセストークンのみを受け付け、状態の判定はイントロスペクションと同じ規則で行う
                        // （サービストークンがアクセストークンとして使われるのを防ぐ）
                        get<TokenStateValidator>(TokenStateValidator::class.java)
                            .validateBlocking(TokenKind.ACCESS, payload)
                            .fold(
                                { failure ->
                                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, failure.authResult)
                                    null
                                },
                                {
                                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, "valid")
                                    JWTPrincipal(payload)
                                }
                            )
                    }
                }
            }
            challenge { _, _ ->
                // RFC 6750 Section 3のWWW-Authenticateとドキュメント記載のエラーエンベロープ（error/code）を返す
                BearerChallenge.respondProviderFailure(call, "Token is not valid or has expired")
            }
        }

        // サービスアカウントトークンのJWT検証
        jwt(JwtCompleteCode.SERVICE_TOKEN.code) {
            realm = jwtConfigData.realm
            // ローカルの公開鍵で署名を検証し、自サーバーとtrustedIssuersのissを受け付ける
            verifier(JwtProvider.bearerVerifier)

            validate { credential ->
                // JWT検証の過程をスパンとして記録する（トークン本体は記録しない）
                withSpan("jwt.validate.service_token") { span ->
                    // 想定外の例外はchallengeを素通りしてしまうので、無効なトークンとして拒否する
                    BearerChallenge.failClosed(span, plugin.logger) {
                        val payload = credential.payload

                        // サービストークンのみを受け付け、状態の判定はイントロスペクションと同じ規則で行う
                        get<TokenStateValidator>(TokenStateValidator::class.java)
                            .validateBlocking(TokenKind.SERVICE, payload)
                            .fold(
                                { failure ->
                                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, failure.authResult)
                                    null
                                },
                                {
                                    // 最終使用日時を更新（トークン監査用）
                                    // イントロスペクションを使用として数えないよう、状態の判定とは分けてここで行う
                                    payload.id?.let { ServiceAccountTokenRepository.updateLastUsedAtBlocking(it) }

                                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, "valid")
                                    JWTPrincipal(payload)
                                }
                            )
                    }
                }
            }
            challenge { _, _ ->
                // RFC 6750 Section 3のWWW-Authenticateとドキュメント記載のエラーエンベロープ（error/code）を返す
                BearerChallenge.respondProviderFailure(call, "Service token is not valid or has expired")
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
