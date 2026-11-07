package party.morino.mineauth.core.file.load.config

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.utils.json
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ConfigLoaderのserver.baseUrl検証のテスト
 * 不正な値では以前の設定を残して失敗し、httpsでない公開ホストでは警告だけが出ることを確認する
 *
 * MockBukkitは使わず、Koinにモックのプラグインと一時ディレクトリを登録する
 */
class ConfigLoaderTest {
    @TempDir
    lateinit var dataFolder: File

    private val logHandler = RecordingLogHandler()

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

    /**
     * 指定したbaseUrlのconfig.jsonを書き込む
     * pepperの生成による書き戻しが起きないよう、pepperは設定しておく
     */
    private fun writeConfig(baseUrl: String) {
        val config = MineAuthConfig(
            server = ServerConfig(baseUrl = baseUrl),
            password = PasswordConfig(pepper = "pepper"),
        )
        dataFolder.resolve("config.json").writeText(json.encodeToString(config))
    }

    @Test
    @DisplayName("Registers the normalized issuer without a warning for https")
    fun loadsHttpsBaseUrl() {
        writeConfig("https://api.example.com/main/")

        ConfigLoader().load()

        assertEquals("https://api.example.com/main", GlobalContext.get().get<JWTConfigData>().issuer)
        assertEquals(emptyList(), logHandler.warnings)
    }

    @Test
    @DisplayName("Logs a warning for http on a public host and keeps loading")
    fun warnsAboutPublicHttp() {
        writeConfig("http://api.example.com")

        ConfigLoader().load()

        assertEquals("http://api.example.com", GlobalContext.get().get<JWTConfigData>().issuer)
        assertTrue(logHandler.warnings.single().contains("does not use https"))
    }

    @Test
    @DisplayName("Does not warn about http on localhost")
    fun doesNotWarnAboutLoopbackHttp() {
        writeConfig("http://localhost:8080")

        ConfigLoader().load()

        assertEquals(emptyList(), logHandler.warnings)
    }

    @Test
    @DisplayName("Fails with a clear error and keeps the previous config for a query")
    fun failsOnQueryAndKeepsPreviousConfig() {
        writeConfig("https://api.example.com")
        ConfigLoader().load()

        writeConfig("https://api.example.com/next?tenant=a")
        val error = assertThrows<IllegalStateException> { ConfigLoader().load() }

        assertTrue(error.message.orEmpty().contains("must not contain a query"))
        // Koinには以前の設定が残るので、ReloadCommandはその設定でHTTPサーバーを再開できる
        assertEquals("https://api.example.com", GlobalContext.get().get<JWTConfigData>().issuer)
    }

    @Test
    @DisplayName("Does not create a backup when the base URL is rejected")
    fun doesNotRewriteConfigWhenRejected() {
        // pepper未設定のconfigは書き戻されるが、検証に失敗した場合は触れない
        val configFile = dataFolder.resolve("config.json")
        configFile.writeText(json.encodeToString(MineAuthConfig(server = ServerConfig(baseUrl = "https://api.example.com#top"))))
        val before = configFile.readText()

        assertThrows<IllegalStateException> { ConfigLoader().load() }

        assertEquals(before, configFile.readText())
        assertFalse(dataFolder.resolve("config.json.bak").exists())
    }
}
