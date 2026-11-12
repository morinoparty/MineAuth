package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.ObservabilityConfig
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.web.ratelimit.RateLimitTestSupport
import party.morino.mineauth.core.web.router.auth.oauth.EndSessionRouter.endSessionRouter
import party.morino.mineauth.core.web.router.auth.oauth.IntrospectRouter.introspectRouter
import party.morino.mineauth.core.web.router.auth.oauth.RevokeRouter.revokeRouter
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.tokenRouter
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

/**
 * OAuthエンドポイントのルートテストの共通基盤
 *
 * テストごとに実際のSQLiteへ接続し、Koinにはモックのプラグインとテスト用の鍵で作ったVerifierを登録する。
 * BukkitサーバーとOpenTelemetry SDKには依存しない
 */
abstract class OAuthRouteTestBase {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUpDatabaseAndKoin() {
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()

        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("OAuthRouteTest")
        every { plugin.dataFolder } returns tempDir.toFile()

        // 他のテストが残したKoinの影響を受けないよう、起動前に停止しておく
        stopKoin()
        startKoin {
            modules(
                module {
                    single<MineAuth> { plugin }
                    single { JwtTestSupport.verifiers }
                    single { JWTConfigData(issuer = JwtTestSupport.ISSUER, keyId = UUID.randomUUID()) }
                    single { RedeemedCodeStore() }
                    // テレメトリとメトリクスを無効にし、OpenTelemetry SDKとBukkitのサーバーに触れないようにする
                    single { MineAuthConfig(observability = ObservabilityConfig(enabled = false, metricsEnabled = false)) }
                    single { TokenStateValidator() }
                    single { RefreshTokenResponseCache() }
                    single { RateLimitTestSupport.disabled() }
                    single { Argon2Gate() }
                }
            )
        }
    }

    @AfterEach
    fun tearDownKoin() {
        stopKoin()
    }

    /**
     * 指定した種別のOAuthクライアントを作成する
     *
     * @param clientType クライアント種別
     * @param redirectUris 登録するリダイレクトURI
     * @return 作成結果（Confidentialの場合は平文シークレット付き）
     */
    protected suspend fun registerClient(
        clientType: ClientType,
        redirectUris: List<String> = listOf("https://rp.example.com/logged-out")
    ): OAuthClientCreationResult {
        val account = AccountRepository.create(AccountType.PLAYER, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        return OAuthClientRepository.create("Test Client", clientType, redirectUris, account.accountId)
            .getOrElse { error("Failed to create client: $it") }
    }

    /**
     * イントロスペクションを許可されたリソースサーバーを登録する
     *
     * @return 作成結果（平文シークレット付き）
     */
    protected suspend fun registerResourceServer(): OAuthClientCreationResult {
        val resourceServer = registerClient(ClientType.CONFIDENTIAL)
        OAuthClientRepository.setIntrospectionAllowed(resourceServer.client.clientId, allowed = true)
            .getOrElse { error("Failed to grant the permission: $it") }
        return resourceServer
    }

    /**
     * OAuthのルートだけを持つテストアプリケーションを実行する
     *
     * @param block テスト本体
     */
    protected fun oauthTestApplication(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) {
                // 本番のpluginModuleと同じく、nullのフィールドは出力しない
                json(Json { explicitNulls = false; ignoreUnknownKeys = true })
            }
            routing {
                route("/oauth2") {
                    tokenRouter()
                    revokeRouter()
                    introspectRouter()
                    endSessionRouter()
                }
            }
        }
        block()
    }

    /**
     * レスポンスボディをJSONオブジェクトとして読む
     *
     * @return JSONオブジェクト
     */
    protected suspend fun HttpResponse.jsonBody(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject
}
