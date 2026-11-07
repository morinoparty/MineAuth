package party.morino.mineauth.core.repository

import arrow.core.getOrElse
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.database.RevokedGrants
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.model.TokenLifetimes
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RevocationRecordCleanupのテスト
 * 検証を通らなくなったトークンのレコードだけが消え、失敗が記録されて処理が止まらないことを確認する
 */
class RevocationRecordCleanupTest {

    @TempDir
    lateinit var tempDir: Path

    private val logs = RecordingLogHandler()
    private val cleanup = RevocationRecordCleanup(logs.newLogger())

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()
    }

    /**
     * トークン自身の有効期限を指定して失効させる
     *
     * @return トークンのJWT ID
     */
    private suspend fun revokedTokenExpiringAt(expiresAt: LocalDateTime): String {
        val tokenId = UUID.randomUUID().toString()
        RevokedTokenRepository.revoke(tokenId, TokenType.ACCESS_TOKEN, "client", expiresAt)
            .getOrElse { error("Failed to revoke: $it") }
        return tokenId
    }

    /**
     * 削除日時を指定して失効グラントのレコードを作る
     *
     * @return グラントID
     */
    private fun revokedGrantExpiringAt(expiresAt: LocalDateTime): String {
        val grantId = UUID.randomUUID().toString()
        transaction {
            RevokedGrants.insert {
                it[RevokedGrants.grantId] = grantId
                it[clientId] = "client"
                it[RevokedGrants.expiresAt] = expiresAt
            }
        }
        return grantId
    }

    private fun grantRecordExists(grantId: String): Boolean = transaction {
        RevokedGrants.selectAll().where { RevokedGrants.grantId eq grantId }.any()
    }

    @Test
    @DisplayName("Removes only token records past the expiry and the margin")
    fun removesOnlyExpiredTokenRecords() = runTest {
        val now = LocalDateTime.now()
        val longExpired = revokedTokenExpiringAt(now.minus(TokenLifetimes.REVOCATION_RECORD_MARGIN).minusHours(1))
        // Bearer検証の猶予の間はトークンがまだ検証を通るので、期限を過ぎた直後のレコードは残す
        val justExpired = revokedTokenExpiringAt(now.minusSeconds(1))
        val active = revokedTokenExpiringAt(now.plusHours(1))

        cleanup.run()

        assertFalse(RevokedTokenRepository.isRevoked(longExpired))
        assertTrue(RevokedTokenRepository.isRevoked(justExpired))
        assertTrue(RevokedTokenRepository.isRevoked(active))
    }

    @Test
    @DisplayName("Removes only grant records past their expiry")
    fun removesOnlyExpiredGrantRecords() = runTest {
        val now = LocalDateTime.now()
        val expired = revokedGrantExpiringAt(now.minusMinutes(1))
        val active = revokedGrantExpiringAt(now.plusMinutes(1))

        cleanup.run()

        assertFalse(grantRecordExists(expired))
        assertTrue(grantRecordExists(active))
    }

    @Test
    @DisplayName("A failure is logged and the other table is still cleaned up")
    fun failureIsLoggedAndDoesNotStopTheOtherTable() = runTest {
        val expired = revokedGrantExpiringAt(LocalDateTime.now().minusMinutes(1))
        // 片方の削除を確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_tokens") }

        cleanup.run()

        assertEquals(1, logs.severes.size)
        assertTrue(logs.severes.single().contains("revoked_tokens"))
        assertFalse(grantRecordExists(expired))
    }
}
