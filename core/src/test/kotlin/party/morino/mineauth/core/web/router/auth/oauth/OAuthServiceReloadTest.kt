package party.morino.mineauth.core.web.router.auth.oauth

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
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
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.utils.PlayerPasswordHasher
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.router.auth.common.AuthenticationError
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.test.assertEquals

/**
 * /mineauth reload 後のログイン検証のテスト
 * ConfigLoaderで設定を読み直すと、OAuthServiceが新しいpepperで検証することを確認する
 */
class OAuthServiceReloadTest {
    @TempDir
    lateinit var dataFolder: File

    private val playerId = UUID.randomUUID()
    private val source = ClientIpKey("203.0.113.1")

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("OAuthServiceReloadTest")
        every { plugin.dataFolder } returns dataFolder
        startKoin {
            modules(
                module {
                    single { plugin }
                    single { Argon2Gate() }
                }
            )
        }

        Database.connect(
            url = "jdbc:sqlite:${dataFolder.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()

        val offlinePlayer = mockk<OfflinePlayer>()
        every { offlinePlayer.hasPlayedBefore() } returns true
        every { offlinePlayer.uniqueId } returns playerId
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer("Steve") } returns offlinePlayer
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Bukkit::class)
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
     * 現在のハッシュ化クラスでプレイヤーのパスワードを登録する
     */
    private fun registerPlayer(password: String) {
        val hashed = GlobalContext.get().get<PlayerPasswordHasher>().hash(password)
        transaction {
            UserAuthData.insert {
                it[uuid] = playerId.toString()
                it[UserAuthData.password] = hashed
            }
        }
    }

    @Test
    @DisplayName("Login verifies with the new pepper after reload")
    fun loginUsesNewPepperAfterReload() = runTest {
        loadConfig("first-pepper")
        registerPlayer("password")
        // 再読み込み前に一度使っても、ハッシュ化クラスを保持し続けないことを確かめる
        assertEquals(AuthenticationResult.Success(playerId), OAuthService.authenticateUser("Steve", "password", source))

        loadConfig("second-pepper")

        assertEquals(
            AuthenticationResult.Failed(AuthenticationError.INVALID_PASSWORD),
            OAuthService.authenticateUser("Steve", "password", source)
        )
    }
}
