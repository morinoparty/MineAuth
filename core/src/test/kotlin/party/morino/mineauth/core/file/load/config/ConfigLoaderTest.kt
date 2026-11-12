package party.morino.mineauth.core.file.load.config

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.SerializationException
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
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ConfigLoaderのserver.baseUrl検証と、config.jsonの解釈失敗のテスト
 * 不正なbaseUrlでは以前の設定を残して失敗し、解釈できないconfig.jsonではパーサーが引用した内容が例外に残らないことを確認する
 *
 * Bukkitサーバーは使わず、Koinにモックのプラグインと一時ディレクトリを登録する
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
    @DisplayName("A parse error next to a secret fails without quoting the secret")
    fun parseErrorDoesNotQuoteTheSecret() {
        val secret = "S3cr3tPepperValue"
        // pepperの直後の数値の項目に、数値でない値を書いたconfig.json
        // （isLenientの設定ではカンマの抜けなどは受け入れられるため、型の誤りでパーサーを失敗させる）
        val text = """{"password":{"pepper":"$secret","memory":"lots"}}"""
        dataFolder.resolve("config.json").writeText(text)
        // 前提: パーサーのメッセージそのものは、エラー位置の前後としてパスワードを引用する
        val parserFailure = assertThrows<SerializationException> { json.decodeFromString<MineAuthConfig>(text) }
        assertContains(parserFailure.message.orEmpty(), secret)

        val failure = assertThrows<ConfigParseException> { ConfigLoader().load() }

        // オフセットとJSONパスは残す
        assertContains(failure.message.orEmpty(), "offset")
        assertContains(failure.message.orEmpty(), "at path: \$.password.memory")
        // 原因を添付せず、ログに出るスタックトレースの全文にも引用が残らない
        assertNull(failure.cause)
        assertFalse(secret in failure.stackTraceToString(), failure.stackTraceToString())
    }
}
