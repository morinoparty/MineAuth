package party.morino.mineauth.core.repository

import arrow.core.left
import arrow.core.right
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
import party.morino.mineauth.core.database.RevokedTokens
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.TimeZone
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RevokedTokenRepositoryのテスト
 * ローテーションが使う条件付きの失効と、失効した時刻の記録と読み取りを実際のSQLiteで確認する
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

    @Test
    @DisplayName("The instant is stored as epoch millis and revoked_at in server local time")
    fun revocationTimeIsStoredAsEpochMillisAndLocalTime() = runTest {
        val previousZone = TimeZone.getDefault()
        // UTCとの差が大きいタイムゾーンにして、revoked_atがUTCではなくサーバーのタイムゾーンで書かれることを見分ける
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
        try {
            val before = System.currentTimeMillis()
            // /oauth2/revokeと同じく、失効日時を指定せずに失効させる
            RevokedTokenRepository.revoke(tokenId, TokenType.ACCESS_TOKEN, "client", expiresAt)
            val after = System.currentTimeMillis()

            val row = transaction {
                RevokedTokens.selectAll().where { RevokedTokens.tokenId eq tokenId }.single()
            }
            // 判定に使う時刻はタイムゾーンを持たないエポックミリ秒で残る
            val storedMillis = assertNotNull(row[RevokedTokens.revokedAtEpochMillis])
            assertTrue(storedMillis in before..after, "stored=$storedMillis, expected between $before and $after")
            // revoked_atは同じ時刻をサーバーのタイムゾーンで表した日時になる（他の日時カラムと同じ）
            val expectedLocal = LocalDateTime.ofInstant(Instant.ofEpochMilli(storedMillis), ZoneId.of("Asia/Tokyo"))
            assertEquals(expectedLocal, row[RevokedTokens.revokedAt].truncatedTo(ChronoUnit.MILLIS))
            assertEquals(
                TokenRevocationTime.Recorded(Instant.ofEpochMilli(storedMillis)).right(),
                RevokedTokenRepository.findRevokedAt(tokenId)
            )
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    @DisplayName("A record without revoked_at_ms reads as revoked at an unrecorded time")
    fun recordWithoutEpochMillisIsUnrecorded() = runTest {
        // revoked_at_msの追加前や、追加前のバージョンが書いた記録を再現する（revoked_atだけを持つ）
        // insertのレシーバーのカラムと名前が重なるため、テストのプロパティは明示して参照する
        transaction {
            RevokedTokens.insert {
                it[RevokedTokens.tokenId] = this@RevokedTokenRepositoryTest.tokenId
                it[RevokedTokens.tokenType] = TokenType.REFRESH_TOKEN.value
                it[RevokedTokens.clientId] = "client"
                it[RevokedTokens.revokedAt] = LocalDateTime.now()
                it[RevokedTokens.expiresAt] = this@RevokedTokenRepositoryTest.expiresAt
            }
        }

        // revoked_atがあっても判定には使わず、時刻の分からない記録として返す
        assertEquals(TokenRevocationTime.Unrecorded.right(), RevokedTokenRepository.findRevokedAt(tokenId))
    }

    @Test
    @DisplayName("Reading the revocation time tells a missing record from a failed read")
    fun readDistinguishesMissingFromFailed() = runTest {
        assertEquals(TokenRevocationTime.NotRevoked.right(), RevokedTokenRepository.findRevokedAt(tokenId))

        // 読み取りを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_tokens") }

        assertTrue(RevokedTokenRepository.findRevokedAt(tokenId).leftOrNull() is RevokedTokenError.DatabaseError)
        // Bearer認証とイントロスペクションが使う判定は、従来どおり失効済みとして扱う
        assertTrue(RevokedTokenRepository.isRevoked(tokenId))
    }
}
