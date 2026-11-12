package party.morino.mineauth.core.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import io.mockk.verifyOrder
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.web.WebServer
import kotlin.test.assertContains
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

    // PlainTextComponentSerializerはクラスパス上のプロバイダーによって結果が変わりうるため、自前で連結する
    private fun Component.displayedText(): String =
        (this as? TextComponent)?.content().orEmpty() + children().joinToString("") { it.displayedText() }

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
}
