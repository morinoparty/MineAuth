package party.morino.mineauth.core.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.file.load.config.ConfigParseException
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.web.WebServer
import java.io.IOException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /mineauth reload の手順と失敗時の報告のテスト
 * WebServerとFileUtilsをモックし、どの段階で失敗してもHTTPサーバーを再開することを確認する
 */
class ReloadCommandTest {
    private val logHandler = RecordingLogHandler()

    // コマンドが実行者へ送ったメッセージ
    private val messages = mutableListOf<String>()
    private val sender = mockk<CommandSender>(relaxed = true)

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns logHandler.newLogger()
        startKoin {
            modules(
                module {
                    single { plugin }
                    single { MineAuthConfig() }
                }
            )
        }
        every { sender.sendRichMessage(capture(messages)) } returns Unit

        mockkObject(WebServer, FileUtils)
        every { WebServer.stopServer() } returns Unit
        every { WebServer.settingServer() } returns Unit
        every { WebServer.startServer() } returns Unit
        every { FileUtils.loadFiles() } returns Unit
        every { FileUtils.settingDatabase() } returns Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(WebServer, FileUtils)
        stopKoin()
    }

    /** 送信したメッセージを画面に表示される文字列へ変換する */
    private fun renderedMessages(): List<String> = messages.map { MiniMessage.miniMessage().deserialize(it).displayedText() }

    // PlainTextComponentSerializerはMockBukkitのプロバイダーを読み込んで初期化に失敗するため、自前で連結する
    private fun Component.displayedText(): String =
        (this as? TextComponent)?.content().orEmpty() + children().joinToString("") { it.displayedText() }

    @Test
    @DisplayName("Stops the server before loading and starts it after connecting")
    fun reloadsInOrder() {
        ReloadCommand().reload(sender)

        verifyOrder {
            WebServer.stopServer()
            FileUtils.loadFiles()
            FileUtils.settingDatabase()
            WebServer.settingServer()
            WebServer.startServer()
        }
        assertEquals("Reloaded", renderedMessages().last())
        assertEquals(emptyList(), logHandler.severes)
    }

    @Test
    @DisplayName("A load failure still starts the server and tells the sender")
    fun loadFailureStartsServer() {
        val failure = IllegalStateException("server.baseUrl must not contain a query")
        every { FileUtils.loadFiles() } throws failure

        ReloadCommand().reload(sender)

        verifyOrder {
            WebServer.stopServer()
            FileUtils.loadFiles()
            WebServer.settingServer()
            WebServer.startServer()
        }
        verify(exactly = 0) { FileUtils.settingDatabase() }
        val rendered = renderedMessages()
        assertContains(rendered, "Reload failed: server.baseUrl must not contain a query")
        assertTrue(rendered.any { "previous settings stay active" in it })
        assertFalse("Reloaded" in rendered)
        // 構文エラー以外の失敗はスタックトレースごとログに残る
        assertContains(logHandler.severes.single(), failure.stackTraceToString())
    }

    @Test
    @DisplayName("A config.json syntax error logs its position but not the quoted input")
    fun parseFailureLogsOnlyFirstLine() {
        val secret = "db-password-do-not-log"
        // パスワードの直後のカンマが抜けたconfig.jsonと同じ例外を、実際のパーサーで作る
        val failure = assertThrows<SerializationException> {
            Json.parseToJsonElement("""{"database":{"password":"$secret" "port":3306}}""")
        }
        // 前提: パーサーのメッセージは2行目以降にエラー位置の前後の入力を含む
        assertContains(failure.message.orEmpty(), secret)
        every { FileUtils.loadFiles() } throws failure

        ReloadCommand().reload(sender)

        verify { WebServer.startServer() }
        val logged = logHandler.severes.single()
        // オフセットとJSONパスを含む1行目は残し、引用された入力は残さない
        assertContains(logged, failure.message.orEmpty().lines().first())
        assertFalse(secret in logged, logged)
        assertFalse(messages.any { secret in it })
    }

    @Test
    @DisplayName("A config.json parse failure is logged with the same line as at startup")
    fun configParseFailureLogsItsMessageOnly() {
        // ConfigLoaderが送出するのと同じ、引用を取り除いた例外を実際のパーサーの失敗から作る
        val failure = ConfigParseException.of(
            "config.json",
            assertThrows<SerializationException> { Json.parseToJsonElement("""{"database":{"port":3306 "host":"db"}}""") }
        )
        every { FileUtils.loadFiles() } throws failure

        ReloadCommand().reload(sender)

        verify { WebServer.startServer() }
        // 設定の誤りなので、メッセージだけを残しスタックトレースは残さない
        val logged = logHandler.severes.single()
        assertContains(logged, failure.message.orEmpty())
        assertFalse(failure.stackTraceToString() in logged, logged)
        assertContains(renderedMessages(), "Reload failed: ${failure.message}")
    }

    @Test
    @DisplayName("A database failure still starts the server and says the new config is active")
    fun databaseFailureStartsServer() {
        // ConfigLoaderと同じく、読み込みに成功すると新しい設定が登録される
        every { FileUtils.loadFiles() } answers { loadKoinModules(module { single { MineAuthConfig() } }) }
        every { FileUtils.settingDatabase() } throws IllegalStateException("Connection refused")

        ReloadCommand().reload(sender)

        verify { WebServer.startServer() }
        val rendered = renderedMessages()
        assertContains(rendered, "Reload failed: Connection refused")
        assertTrue(rendered.any { "new config.json is active" in it && "database connection stays" in it })
        assertFalse(rendered.any { "previous settings stay active" in it })
    }

    @Test
    @DisplayName("Exception text cannot inject tags and only its first line reaches the sender")
    fun escapesExceptionText() {
        val summary = "Bad value <click:run_command:'/op attacker'><red>token</red> C:\\config\\"
        every { FileUtils.loadFiles() } throws IllegalArgumentException("$summary\nJSON input: {\"pepper\":\"secret\"}")

        ReloadCommand().reload(sender)

        // タグが解釈されると表示からタグの文字列が消えるため、表示が元の文字列と一致すればエスケープされている
        assertContains(renderedMessages(), "Reload failed: $summary")
        assertFalse(messages.any { "secret" in it })
    }

    @Test
    @DisplayName("A failed restart is reported to the sender and the log")
    fun restartFailureIsReported() {
        val failure = IOException("Failed to bind to /0.0.0.0:8080")
        every { WebServer.startServer() } throws failure

        ReloadCommand().reload(sender)

        val rendered = renderedMessages()
        assertContains(rendered, "The HTTP server could not be started: Failed to bind to /0.0.0.0:8080")
        assertFalse("Reloaded" in rendered)
        assertContains(logHandler.severes.single(), failure.stackTraceToString())
    }

    @Test
    @DisplayName("Cancellation is not swallowed and the server is still started")
    fun cancellationPropagatesAfterStart() {
        every { FileUtils.loadFiles() } throws CancellationException("cancelled")

        assertThrows<CancellationException> { ReloadCommand().reload(sender) }

        verify { WebServer.startServer() }
    }
}
