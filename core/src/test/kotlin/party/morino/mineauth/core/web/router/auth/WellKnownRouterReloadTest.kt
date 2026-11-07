package party.morino.mineauth.core.web.router.auth

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.file.load.config.ConfigLoader
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.web.components.auth.OIDCDiscoveryResponse
import party.morino.mineauth.core.web.router.auth.WellKnownRouter.wellKnownRouter
import java.io.File
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /mineauth reload 後のOIDC Discoveryのテスト
 * トークンのissと同じく、Discoveryのissuerとスコープも再読み込みした設定に追従することを確認する
 */
class WellKnownRouterReloadTest {
    @TempDir
    lateinit var dataFolder: File

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("WellKnownRouterReloadTest")
        every { plugin.dataFolder } returns dataFolder
        startKoin {
            modules(module { single { plugin } })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /**
     * config.jsonを書き込み、/mineauth reload と同じくConfigLoaderで読み直す
     */
    private fun loadConfig(baseUrl: String, emailFormat: String? = null) {
        val config = MineAuthConfig(
            server = ServerConfig(baseUrl = baseUrl, emailFormat = emailFormat),
            password = PasswordConfig(pepper = "pepper"),
        )
        dataFolder.resolve("config.json").writeText(json.encodeToString(config))
        ConfigLoader().load()
    }

    private suspend fun ApplicationTestBuilder.discovery(): OIDCDiscoveryResponse {
        val body = client.get("/.well-known/openid-configuration").bodyAsText()
        return Json { ignoreUnknownKeys = true }.decodeFromString(body)
    }

    @Test
    @DisplayName("Discovery follows the issuer and email setting after reload")
    fun discoveryFollowsReload() = testApplication {
        application {
            install(ContentNegotiation) { json() }
            routing { wellKnownRouter() }
        }

        loadConfig("https://first.example.com")
        val before = discovery()
        assertEquals("https://first.example.com", before.issuer)
        assertFalse("email" in before.scopesSupported)

        loadConfig("https://second.example.com", emailFormat = "<uuid>@example.com")
        val after = discovery()

        assertEquals("https://second.example.com", after.issuer)
        assertEquals("https://second.example.com/.well-known/jwks.json", after.jwksUri)
        assertTrue("email" in after.scopesSupported)
    }
}
