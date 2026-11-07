package party.morino.mineauth.core.repository

import arrow.core.getOrElse
import arrow.core.right
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
import party.morino.mineauth.core.model.TokenLifetimes
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RevokedGrantRepositoryのテスト
 * 失効の冪等性、読み取り失敗時の扱い、レコードの保持期限を実際のSQLiteで確認する
 */
class RevokedGrantRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private val grantId = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()
    }

    private fun expiresAtOf(grantId: String): LocalDateTime = transaction {
        RevokedGrants.selectAll()
            .where { RevokedGrants.grantId eq grantId }
            .single()[RevokedGrants.expiresAt]
    }

    @Test
    @DisplayName("Revoking twice succeeds and keeps the first record")
    fun revokeIsIdempotent() = runTest {
        assertFalse(RevokedGrantRepository.isRevoked(grantId))

        assertEquals(Unit.right(), RevokedGrantRepository.revoke(grantId, "client"))
        val firstExpiry = expiresAtOf(grantId)
        assertEquals(Unit.right(), RevokedGrantRepository.revoke(grantId, "client"))

        assertTrue(RevokedGrantRepository.isRevoked(grantId))
        assertEquals(firstExpiry, expiresAtOf(grantId))
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

    @Test
    @DisplayName("A failed read counts as revoked and a failed write is reported")
    fun databaseFailuresFailClosed() = runTest {
        // 読み書きを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_grants") }

        assertTrue(RevokedGrantRepository.isRevoked(grantId))
        assertTrue(RevokedGrantRepository.revoke(grantId, "client").isLeft())
        // refresh_tokenグラント向けの読み取りは、失効済みとは区別して失敗を返す
        assertTrue(RevokedGrantRepository.checkRevoked(grantId).isLeft())
    }

    @Test
    @DisplayName("Reuse revokes the grant only when the token has a revocation record")
    fun reuseNeedsARevocationRecord() = runTest {
        val tokenId = UUID.randomUUID().toString()

        assertEquals(false.right(), RevokedGrantRepository.revokeForReusedToken(grantId, "client", tokenId))
        assertFalse(RevokedGrantRepository.isRevoked(grantId))

        RevokedTokenRepository.revoke(tokenId, TokenType.REFRESH_TOKEN, "client", LocalDateTime.now().plusDays(1))
            .getOrElse { error("Failed to revoke: $it") }

        assertEquals(true.right(), RevokedGrantRepository.revokeForReusedToken(grantId, "client", tokenId))
        assertTrue(RevokedGrantRepository.isRevoked(grantId))
    }
}
