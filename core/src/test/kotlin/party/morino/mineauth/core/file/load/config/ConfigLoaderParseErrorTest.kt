package party.morino.mineauth.core.file.load.config

import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.utils.json
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * ConfigLoaderがconfig.jsonを解釈できなかった場合のテスト
 * 起動時と /mineauth reload のどちらのログにも、パーサーが引用したconfig.jsonの内容が残らないことを確認する
 *
 * MockBukkitは使わず、Koinにモックのプラグインと一時ディレクトリを登録する
 */
class ConfigLoaderParseErrorTest {
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

    @Test
    @DisplayName("A secret written as a map key is not quoted in the error")
    fun mapKeySecretIsNotQuoted() {
        val secret = "S3cretJdbcPassword"
        // database.propertiesに「キー=値」の形でシークレットを書き、値を書き忘れたconfig.json
        val text = """{"database":{"type":"mysql","properties":{"useSSL":"true","password=$secret"}}}"""
        dataFolder.resolve("config.json").writeText(text)
        // 前提: パーサーの1行目そのものは、JSONパスにマップのキーを引用する
        val parserFailure = assertThrows<SerializationException> { json.decodeFromString<MineAuthConfig>(text) }
        assertContains(parserFailure.message.orEmpty().lines().first(), secret)

        val failure = assertThrows<ConfigParseException> { ConfigLoader().load() }

        assertFalse(secret in failure.stackTraceToString(), failure.stackTraceToString())
        assertContains(failure.message.orEmpty(), "at path: \$.database.properties['<redacted>']")
    }
}
