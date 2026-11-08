package party.morino.mineauth.core.database

import arrow.core.getOrElse
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientRepository
import java.nio.file.Path
import java.sql.SQLException
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * oauth_clients.introspection_allowed のマイグレーションのテスト
 * 実際のSQLiteファイルに旧スキーマを作り、既存の行を壊さずにカラムが追加されることを確認する（Issue #480）
 */
class DatabaseMigratorIntrospectionColumnTest {

    @TempDir
    lateinit var tempDir: Path

    // テストごとに同じ値で比較するため、旧スキーマの行の作成日時を固定する
    private val storedAt = LocalDateTime.of(2024, 1, 2, 3, 4, 5)
    private val storedAtText = "2024-01-02 03:04:05.000"

    @BeforeEach
    fun setUp() {
        // テストごとに新しいSQLiteファイルへ接続する
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
    }

    /**
     * introspection_allowed を追加する前のスキーマと既存のクライアント1件を生のSQLで再現する
     * テーブル定義を変更しても影響を受けないよう、当時Exposedが生成したDDLをそのまま書く
     */
    private fun createOldSchema() {
        transaction {
            exec(
                "CREATE TABLE accounts (account_id VARCHAR(36) NOT NULL PRIMARY KEY, account_type VARCHAR(20) NOT NULL, " +
                    "identifier VARCHAR(64) NOT NULL, created_at TEXT NOT NULL)"
            )
            exec(
                "CREATE TABLE oauth_clients (client_id VARCHAR(36) NOT NULL PRIMARY KEY, client_name VARCHAR(255) NOT NULL, " +
                    "client_type VARCHAR(20) NOT NULL, client_secret_hash VARCHAR(255) NULL, " +
                    "issuer_account_id VARCHAR(36) NOT NULL, " +
                    "created_at TEXT NOT NULL, updated_at TEXT NOT NULL, " +
                    "CONSTRAINT fk_oauth_clients_issuer_account_id__account_id FOREIGN KEY (issuer_account_id) " +
                    "REFERENCES accounts(account_id) ON DELETE CASCADE ON UPDATE RESTRICT)"
            )
            exec("INSERT INTO accounts VALUES ('account-1', 'player', 'identifier-1', '$storedAtText')")
            exec(
                "INSERT INTO oauth_clients (client_id, client_name, client_type, client_secret_hash, issuer_account_id, " +
                    "created_at, updated_at) VALUES ('client-1', 'Old Client', 'confidential', 'old-hash', 'account-1', " +
                    "'$storedAtText', '$storedAtText')"
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
     * oauth_clients の実際のカラム定義（名前、型、NOT NULL、デフォルト値、主キー）を読む
     */
    private fun columnDefinitions(): List<Map<String, String?>> = rows("PRAGMA table_info(oauth_clients)")

    private fun introspectionColumn(): Map<String, String?>? =
        columnDefinitions().singleOrNull { it["name"] == "introspection_allowed" }

    @Test
    @DisplayName("Adds the column to an old table once and leaves everything else unchanged")
    fun addsTheColumnToAnOldTable() = runTest {
        createOldSchema()
        val columnsBefore = columnDefinitions()
        val clientBefore = rows("SELECT * FROM oauth_clients")
        val accountsBefore = rows("SELECT * FROM accounts")
        val foreignKeysBefore = rows("PRAGMA foreign_key_list(oauth_clients)")

        val firstRun = DatabaseMigrator.migrate()
        val secondRun = DatabaseMigrator.migrate()

        // 追加したDDLはログに残り、2回目は何もしない
        assertEquals(1, firstRun.count { "introspection_allowed" in it })
        assertTrue(secondRun.isEmpty())

        // 新規作成時と同じく NOT NULL DEFAULT false のカラムになる
        val column = introspectionColumn()
        assertEquals("BOOLEAN", column?.get("type"))
        assertEquals("1", column?.get("notnull"))
        assertEquals("0", column?.get("dflt_value"))

        // 既存のカラム、行、外部キーは変わらない
        assertEquals(columnsBefore, columnDefinitions().filter { it["name"] != "introspection_allowed" })
        assertEquals(clientBefore, rows("SELECT * FROM oauth_clients").map { it - "introspection_allowed" })
        assertEquals(accountsBefore, rows("SELECT * FROM accounts"))
        assertEquals(foreignKeysBefore, rows("PRAGMA foreign_key_list(oauth_clients)"))

        // 既存の行はアプリケーションから読めて、権限を持たない
        val client = OAuthClientRepository.findById("client-1").getOrElse { error("Not found: $it") }
        assertFalse(client.introspectionAllowed)
        assertEquals("Old Client", client.clientName)
        assertEquals(ClientType.CONFIDENTIAL, client.clientType)
        assertEquals(storedAt, client.createdAt)
        assertEquals(storedAt, client.updatedAt)
    }

    @Test
    @DisplayName("Losing the race against another server is not a failure")
    fun losingTheRaceIsNotAFailure() {
        DatabaseMigrator.migrate()
        // 確認した時点ではカラムが無く、ALTERの直前に他のサーバーが追加した状況を再現する
        val existence = ArrayDeque(listOf(false, true))

        val executed = DatabaseMigrator.addColumnIfMissing(
            columnExists = { existence.removeFirst() },
            addColumn = {
                // 実際のDBで重複エラーを起こし、Exposed経由の例外も許容の対象であることを確かめる
                transaction { exec("ALTER TABLE oauth_clients ADD introspection_allowed BOOLEAN DEFAULT 0 NOT NULL") }
                listOf("not expected")
            }
        )

        assertTrue(executed.isEmpty())
    }

    @Test
    @DisplayName("An ALTER failure is rethrown when the column is still missing")
    fun alterFailureIsRethrownWhenTheColumnIsMissing() {
        val failure = SQLException("Access denied")

        val thrown = assertFailsWith<SQLException> {
            DatabaseMigrator.addColumnIfMissing(columnExists = { false }, addColumn = { throw failure })
        }

        assertSame(failure, thrown)
    }
}
