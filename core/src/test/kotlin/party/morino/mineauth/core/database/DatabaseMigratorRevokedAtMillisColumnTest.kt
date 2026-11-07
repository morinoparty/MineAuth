package party.morino.mineauth.core.database

import arrow.core.right
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenRevocationTime
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * revoked_tokens.revoked_at_ms のマイグレーションのテスト
 * 実際のSQLiteファイルに旧スキーマを作り、既存の行を壊さずにカラムが追加されることを確認する
 * （MySQLは実際のサーバーが必要なため、ここでは確認できない）
 */
class DatabaseMigratorRevokedAtMillisColumnTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // テストごとに新しいSQLiteファイルへ接続する
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
    }

    /**
     * revoked_at_ms を追加する前の revoked_tokens と、使用済みのリフレッシュトークン1件を生のSQLで再現する
     * テーブル定義を変更しても影響を受けないよう、当時Exposedが生成したDDLをそのまま書く
     */
    private fun createOldSchema() {
        transaction {
            exec(
                "CREATE TABLE revoked_tokens (token_id VARCHAR(36) NOT NULL PRIMARY KEY, token_type VARCHAR(20) NOT NULL, " +
                    "client_id VARCHAR(36) NOT NULL, revoked_at TEXT NOT NULL, expires_at TEXT NOT NULL)"
            )
            exec(
                "INSERT INTO revoked_tokens VALUES ('token-1', 'refresh_token', 'client-1', " +
                    "'2024-01-02 03:04:05.000', '2024-02-01 03:04:05.000')"
            )
        }
    }

    /**
     * SQLの結果を「カラム名=値」の行として読む
     */
    private fun rows(sql: String): List<Map<String, String?>> = transaction {
        exec(sql) { rs ->
            val metadata = rs.metaData
            buildList {
                while (rs.next()) {
                    add((1..metadata.columnCount).associate { metadata.getColumnName(it) to rs.getString(it) })
                }
            }
        }.orEmpty()
    }

    /**
     * revoked_tokens の実際のカラム定義（名前、型、NOT NULL、デフォルト値、主キー）を読む
     */
    private fun columnDefinitions(): List<Map<String, String?>> = rows("PRAGMA table_info(revoked_tokens)")

    @Test
    @DisplayName("Adds revoked_at_ms to an old table once and keeps its rows")
    fun addsTheColumnToAnOldTable() = runTest {
        createOldSchema()
        val columnsBefore = columnDefinitions()
        val rowsBefore = rows("SELECT * FROM revoked_tokens")

        val firstRun = DatabaseMigrator.migrate()
        val secondRun = DatabaseMigrator.migrate()

        // 追加したDDLはログに残り、2回目は何もしない
        assertEquals(1, firstRun.count { "revoked_at_ms" in it })
        assertTrue(secondRun.isEmpty())

        // 新規作成時と同じく NULL を許す BIGINT のカラムになる
        val column = columnDefinitions().single { it["name"] == "revoked_at_ms" }
        assertEquals("BIGINT", column["type"])
        assertEquals("0", column["notnull"])

        // 既存のカラムと行は変わらず、追加したカラムはNULLのまま
        assertEquals(columnsBefore, columnDefinitions().filter { it["name"] != "revoked_at_ms" })
        val rowsAfter = rows("SELECT * FROM revoked_tokens")
        assertEquals(rowsBefore, rowsAfter.map { it - "revoked_at_ms" })
        assertEquals(listOf(null), rowsAfter.map { it["revoked_at_ms"] })

        // 既存の行は時刻の分からない記録として読まれる（再提示は再利用として扱われる）
        assertEquals(TokenRevocationTime.Unrecorded.right(), RevokedTokenRepository.findRevokedAt("token-1"))
    }

    @Test
    @DisplayName("A new database gets revoked_at_ms without any ALTER")
    fun newDatabaseGetsTheColumnFromCreate() {
        val executed = DatabaseMigrator.migrate()

        assertTrue(executed.isEmpty())
        assertEquals("BIGINT", columnDefinitions().single { it["name"] == "revoked_at_ms" }["type"])
    }
}
