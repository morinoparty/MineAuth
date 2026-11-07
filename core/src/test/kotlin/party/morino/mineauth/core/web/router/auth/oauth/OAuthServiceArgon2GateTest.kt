package party.morino.mineauth.core.web.router.auth.oauth

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.runBlocking
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
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.database.UserAuthData
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.utils.PasswordVerification
import party.morino.mineauth.core.utils.PlayerPasswordHasher
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.router.auth.common.AuthenticationResult
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

/**
 * ログインのパスワード検証がArgon2Gateを通ることのテスト
 * ハッシュ化クラスはモックにし、実際のArgon2は実行しない
 */
class OAuthServiceArgon2GateTest {
    @TempDir
    lateinit var dataFolder: File

    private val playerId = UUID.randomUUID()
    private val gate = Argon2Gate(permits = 1, acquireTimeout = 50.milliseconds)
    private val hasher = mockk<PlayerPasswordHasher>()
    private val source = ClientIpKey("203.0.113.1")
    private val otherSource = ClientIpKey("203.0.113.2")

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("OAuthServiceArgon2GateTest")
        startKoin {
            modules(
                module {
                    single { plugin }
                    single { gate }
                    single { hasher }
                }
            )
        }

        Database.connect(url = "jdbc:sqlite:${dataFolder.resolve("test.db")}", driver = "org.sqlite.JDBC")
        DatabaseMigrator.migrate()
        transaction {
            UserAuthData.insert {
                it[uuid] = playerId.toString()
                it[password] = "stored-hash"
            }
        }

        val offlinePlayer = mockk<OfflinePlayer>()
        every { offlinePlayer.hasPlayedBefore() } returns true
        every { offlinePlayer.uniqueId } returns playerId
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer("Steve") } returns offlinePlayer
        every { hasher.verify("password", "stored-hash") } returns PasswordVerification.MATCHED
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Bukkit::class)
        stopKoin()
    }

    /**
     * 指定した接続元の検証を別のスレッドで実行したまま止め、その間に処理を実行する
     *
     * @param testGate 対象のゲート
     * @param source 検証を要求する接続元
     * @param verifications 止めておく検証の数
     * @param block 検証を止めている間に実行する処理
     */
    private fun whileHolding(testGate: Argon2Gate, source: ClientIpKey, verifications: Int, block: () -> Unit) {
        val holding = CountDownLatch(verifications)
        val release = CountDownLatch(1)
        val holders = List(verifications) {
            thread {
                runBlocking {
                    testGate.withPermit(source) {
                        holding.countDown()
                        release.await(30, TimeUnit.SECONDS)
                    }
                }
            }
        }
        holding.await(30, TimeUnit.SECONDS)
        try {
            block()
        } finally {
            release.countDown()
            holders.forEach { it.join() }
        }
    }

    @Test
    @DisplayName("Login is not evaluated while the gate is full")
    fun fullGateSkipsVerification() {
        // 別の接続元がゲートの唯一の許可を持ち続ける
        whileHolding(gate, otherSource, verifications = 1) {
            assertEquals(AuthenticationResult.VerifierBusy, runBlocking { OAuthService.authenticateUser("Steve", "password", source) })
            verify(exactly = 0) { hasher.verify(any(), any()) }
        }

        // 許可が空くと評価される
        assertEquals(AuthenticationResult.Success(playerId), runBlocking { OAuthService.authenticateUser("Steve", "password", source) })
    }

    @Test
    @DisplayName("Login waits for the slots of its own source and not for other sources")
    fun loginUsesSourceSlots() {
        // 全体の許可には空きがあるゲートに置き換え、接続元の枠だけを埋める
        val roomyGate = Argon2Gate(permits = 4, acquireTimeout = 50.milliseconds)
        loadKoinModules(module { single { roomyGate } })

        whileHolding(roomyGate, source, verifications = 2) {
            assertEquals(AuthenticationResult.VerifierBusy, runBlocking { OAuthService.authenticateUser("Steve", "password", source) })
            assertEquals(
                AuthenticationResult.Success(playerId),
                runBlocking { OAuthService.authenticateUser("Steve", "password", otherSource) }
            )
        }
        verify(exactly = 1) { hasher.verify(any(), any()) }
    }
}
