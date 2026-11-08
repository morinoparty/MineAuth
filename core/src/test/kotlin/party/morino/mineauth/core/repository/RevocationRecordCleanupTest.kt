package party.morino.mineauth.core.repository

import arrow.core.getOrElse
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
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
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RevocationRecordCleanupのテスト
 * 検証を通らなくなったトークンのレコードだけが消えることと、失効グラントのレコードの保持期限を確認する
 */
class RevocationRecordCleanupTest {

    @TempDir
    lateinit var tempDir: Path

    private val grantId = UUID.randomUUID().toString()

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

    private fun expiresAtOf(grantId: String): LocalDateTime = transaction {
        RevokedGrants.selectAll()
            .where { RevokedGrants.grantId eq grantId }
            .single()[RevokedGrants.expiresAt]
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
    @DisplayName("The record is kept past the lifetime of any token of the grant")
    fun recordOutlivesTheTokens() = runTest {
        // DBの精度で切り捨てられても比較できるよう、秒単位に丸めた時刻を基準にする
        val before = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)

        RevokedGrantRepository.revoke(grantId, "client").getOrElse { error("Failed to revoke: $it") }

        val newestTokenExpiry = before.plus(TokenLifetimes.REFRESH_TOKEN)
        assertFalse(expiresAtOf(grantId).isBefore(newestTokenExpiry.plus(TokenLifetimes.REVOCATION_RECORD_MARGIN)))
    }
}
