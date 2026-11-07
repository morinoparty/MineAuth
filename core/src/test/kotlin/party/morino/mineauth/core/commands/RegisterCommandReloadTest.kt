package party.morino.mineauth.core.commands

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.bukkit.entity.Player
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
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
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.database.UserAuthData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.load.config.ConfigLoader
import party.morino.mineauth.core.utils.PasswordVerification
import party.morino.mineauth.core.utils.PlayerPasswordHasher
import party.morino.mineauth.core.utils.json
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals

/**
 * /mineauth reload 後のパスワード発行のテスト
 * 起動時に一度だけ作られるコマンドでも、再読み込み後は新しいpepperでハッシュ化することを確認する
 */
class RegisterCommandReloadTest {
    @TempDir
    lateinit var dataFolder: File

    private val playerId = UUID.randomUUID()

    // コマンドがプレイヤーへ送ったメッセージ（発行したパスワードを含む）
    private val messages = mutableListOf<String>()
    private val player = mockk<Player>(relaxed = true)

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("RegisterCommandReloadTest")
        every { plugin.dataFolder } returns dataFolder
        startKoin {
            modules(module { single { plugin } })
        }

        Database.connect(
            url = "jdbc:sqlite:${dataFolder.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()

        every { player.uniqueId } returns playerId
        every { player.sendRichMessage(capture(messages)) } returns Unit
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /**
     * 指定したpepperのconfig.jsonを書き込み、/mineauth reload と同じくConfigLoaderで読み直す
     */
    private fun loadConfig(pepper: String) {
        val config = MineAuthConfig(password = PasswordConfig(pepper = pepper))
        dataFolder.resolve("config.json").writeText(json.encodeToString(config))
        ConfigLoader().load()
    }

    /**
     * 直近のメッセージから発行されたパスワードを取り出す
     */
    private fun lastIssuedPassword(): String =
        requireNotNull(Regex("""Password is\s+(\w+)""").find(messages.last())).groupValues[1]

    /**
     * データベースに保存されているハッシュを読む
     */
    private fun storedHash(): String = transaction {
        UserAuthData.selectAll()
            .where { UserAuthData.uuid eq playerId.toString() }
            .single()[UserAuthData.password]
    }

    /**
     * 現在のハッシュ化クラスで検証する
     */
    private fun verifyWithCurrentHasher(password: String, hash: String): PasswordVerification =
        GlobalContext.get().get<PlayerPasswordHasher>().verify(password, hash)

    @Test
    @DisplayName("Issued passwords use the new pepper after reload")
    fun issuedPasswordUsesNewPepperAfterReload() = runTest {
        val command = RegisterCommand()
        loadConfig("first-pepper")
        // 再読み込み前に一度使っても、ハッシュ化クラスを保持し続けないことを確かめる
        command.register(player)
        assertEquals(PasswordVerification.MATCHED, verifyWithCurrentHasher(lastIssuedPassword(), storedHash()))

        loadConfig("second-pepper")
        command.change(player)

        assertEquals(PasswordVerification.MATCHED, verifyWithCurrentHasher(lastIssuedPassword(), storedHash()))
    }
}
