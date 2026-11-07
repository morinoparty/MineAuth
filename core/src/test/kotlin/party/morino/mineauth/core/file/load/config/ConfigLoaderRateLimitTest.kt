package party.morino.mineauth.core.file.load.config

import arrow.core.right
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ConfigLoaderのrateLimit設定の読み込みのテスト
 * 既存のconfig.jsonとの互換性と、/mineauth reload での置き換えを確認する
 */
class ConfigLoaderRateLimitTest {
    @TempDir
    lateinit var dataFolder: File

    private val logHandler = RecordingLogHandler()
    private val clientIp = ClientIpKey("203.0.113.7")
    // Publicクライアントのトークンリクエストは、クライアントIPごとのバケットで数えられる
    private val publicClient = ClientData.PublicClientData("public", "Public", emptyList())

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns logHandler.newLogger()
        every { plugin.dataFolder } returns dataFolder
        startKoin {
            modules(module { single { plugin } })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    private fun writeConfig(rateLimit: RateLimitConfig) {
        val config = MineAuthConfig(rateLimit = rateLimit, password = PasswordConfig(pepper = "pepper"))
        dataFolder.resolve("config.json").writeText(json.encodeToString(config))
    }

    private fun currentLimits(): OAuthRateLimits = GlobalContext.get().get()

    @Test
    @DisplayName("An old config.json without the section loads with the defaults")
    fun oldConfigLoads() {
        dataFolder.resolve("config.json").writeText(
            """{"server":{"baseUrl":"https://api.example.com"},"password":{"pepper":"pepper"}}"""
        )

        ConfigLoader().load()

        assertEquals(RateLimitConfig(), GlobalContext.get().get<MineAuthConfig>().rateLimit)
        assertEquals(emptyList(), logHandler.warnings)
        assertEquals(Unit.right(), currentLimits().acquireTokenRequest(clientIp, publicClient))
    }

    @Test
    @DisplayName("A new config.json shows the section with the defaults")
    fun newConfigShowsSection() {
        // pepperの生成で書き戻されるので、書き戻した内容が既定値と一致することも確認できる
        ConfigLoader().load()

        val written = json.decodeFromString<MineAuthConfig>(dataFolder.resolve("config.json").readText())
        assertEquals(RateLimitConfig(), written.rateLimit)
        assertTrue(dataFolder.resolve("config.json").readText().contains("trustedProxies"))
    }

    @Test
    @DisplayName("Warns about each bad trustedProxies entry and keeps loading")
    fun warnsAboutBadEntries() {
        writeConfig(RateLimitConfig(trustedProxies = listOf("10.0.0.0/8", "proxy.example.com", "10.0.0.0/33")))

        ConfigLoader().load()

        assertEquals(2, logHandler.warnings.size)
        assertTrue(logHandler.warnings[0].contains("proxy.example.com"))
        assertTrue(logHandler.warnings[1].contains("10.0.0.0/33"))
        // 有効な値は使われ続ける
        assertEquals(ClientIpKey("203.0.113.7"), currentLimits().resolveClientIp("10.1.2.3", "203.0.113.7"))
    }

    @Test
    @DisplayName("Reload applies new limits and resets the counters")
    fun reloadReplacesLimits() {
        writeConfig(RateLimitConfig(tokenRequestsPerMinutePerIp = 1))
        ConfigLoader().load()
        assertEquals(Unit.right(), currentLimits().acquireTokenRequest(clientIp, publicClient))
        assertTrue(currentLimits().acquireTokenRequest(clientIp, publicClient).isLeft())

        writeConfig(RateLimitConfig(tokenRequestsPerMinutePerIp = 2))
        ConfigLoader().load()

        assertEquals(Unit.right(), currentLimits().acquireTokenRequest(clientIp, publicClient))
        assertEquals(Unit.right(), currentLimits().acquireTokenRequest(clientIp, publicClient))
        assertTrue(currentLimits().acquireTokenRequest(clientIp, publicClient).isLeft())
    }
}
