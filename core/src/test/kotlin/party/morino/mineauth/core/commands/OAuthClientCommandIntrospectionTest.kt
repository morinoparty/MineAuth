package party.morino.mineauth.core.commands

import arrow.core.getOrElse
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.bukkit.command.CommandSender
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.model.ClientId
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientData
import party.morino.mineauth.core.repository.OAuthClientRepository
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /mineauth client introspection コマンドのテスト
 * 権限の変更と、実行者への通知、client info での表示を確認する（Issue #480）
 */
class OAuthClientCommandIntrospectionTest {

    @TempDir
    lateinit var tempDir: Path

    private val command = OAuthClientCommand()

    // コマンドが実行者へ送ったメッセージ
    private val messages = mutableListOf<String>()
    private val sender = mockk<CommandSender>(relaxed = true)

    @BeforeEach
    fun setUp() {
        // テストごとに新しいSQLiteファイルへ接続し、最新のスキーマを作成する
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()

        every { sender.sendRichMessage(capture(messages)) } returns Unit
    }

    /**
     * 指定した種別のクライアントを作成する
     */
    private suspend fun createClient(clientType: ClientType): OAuthClientData {
        val account = AccountRepository.create(AccountType.PLAYER, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        return OAuthClientRepository.create("Test Client", clientType, listOf("https://example.com/cb"), account.accountId)
            .getOrElse { error("Failed to create client: $it") }
            .client
    }

    /**
     * データベースに保存されている権限の状態を読む
     */
    private suspend fun storedIntrospectionAllowed(clientId: String): Boolean =
        OAuthClientRepository.findById(clientId).getOrElse { error("Not found: $it") }.introspectionAllowed

    /**
     * client info が表示するトークンイントロスペクションの行を取得する
     */
    private suspend fun infoLine(clientId: String): String {
        messages.clear()
        command.clientInfo(sender, ClientId(clientId))
        return messages.single { "トークンイントロスペクション" in it }
    }

    @Test
    @DisplayName("Allow and deny change the permission and client info shows the state")
    fun allowAndDenyChangeThePermission() = runTest {
        val clientId = createClient(ClientType.CONFIDENTIAL).clientId
        assertTrue("拒否" in infoLine(clientId))

        command.allowIntrospection(sender, ClientId(clientId))
        assertTrue(storedIntrospectionAllowed(clientId))
        assertTrue(messages.last().startsWith("<green>"))
        assertTrue("許可" in infoLine(clientId))

        command.denyIntrospection(sender, ClientId(clientId))
        assertFalse(storedIntrospectionAllowed(clientId))
        assertTrue(messages.last().startsWith("<green>"))
        assertTrue("拒否" in infoLine(clientId))
    }

    @Test
    @DisplayName("Allow on a public client reports an error and client info shows it as not applicable")
    fun allowOnPublicClientReportsAnError() = runTest {
        val clientId = createClient(ClientType.PUBLIC).clientId

        command.allowIntrospection(sender, ClientId(clientId))

        assertTrue(messages.last().startsWith("<red>"))
        assertTrue("Public" in messages.last())
        assertFalse(storedIntrospectionAllowed(clientId))
        assertTrue("対象外" in infoLine(clientId))
    }
}
