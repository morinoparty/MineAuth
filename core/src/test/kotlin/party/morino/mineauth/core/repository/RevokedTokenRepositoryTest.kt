package party.morino.mineauth.core.repository

import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.database.DatabaseMigrator
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * RevokedTokenRepositoryのテスト
 * ローテーションが使う条件付きの失効を実際のSQLiteで確認する
 */
class RevokedTokenRepositoryTest {

    @TempDir
    lateinit var tempDir: Path

    private val tokenId = UUID.randomUUID().toString()
    private val expiresAt = LocalDateTime.now().plusDays(30)

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()
    }

    @Test
    @DisplayName("Only the first conditional revocation creates the record while revoke stays idempotent")
    fun onlyTheFirstConditionalRevocationWins() = runTest {
        val revokedAt = Instant.parse("2026-01-01T12:00:00Z")

        assertEquals(
            Unit.right(),
            RevokedTokenRepository.revokeIfAbsent(tokenId, TokenType.REFRESH_TOKEN, "client", expiresAt, revokedAt)
        )
        assertEquals(
            RevokedTokenError.AlreadyRevoked.left(),
            RevokedTokenRepository.revokeIfAbsent(tokenId, TokenType.REFRESH_TOKEN, "client", expiresAt)
        )
        // /oauth2/revoke向けのrevokeは、記録が既にあっても成功する
        assertEquals(Unit.right(), RevokedTokenRepository.revoke(tokenId, TokenType.REFRESH_TOKEN, "client", expiresAt))

        // 最初の記録の失効した時刻が残る
        assertEquals(TokenRevocationTime.Recorded(revokedAt).right(), RevokedTokenRepository.findRevokedAt(tokenId))
    }

}
