package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DatabaseMigratorのテスト
 * 旧スキーマ（oauth_clients.redirect_uri）から新テーブルへの移行を確認する
 */
class DatabaseMigratorTest {

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
     * 旧バージョンのスキーマを再現する
     * 現在のテーブル定義で作成した後、削除済みの redirect_uri カラムを追加してデータを入れる
     */
    private fun createLegacySchema(redirectUri: String) {
        transaction {
            SchemaUtils.create(Accounts, OAuthClients)
            exec("ALTER TABLE oauth_clients ADD COLUMN redirect_uri VARCHAR(2048) NOT NULL DEFAULT ''")
            Accounts.insert {
                it[accountId] = "account-1"
                it[accountType] = "player"
                it[identifier] = "00000000-0000-0000-0000-000000000001"
            }
            OAuthClients.insert {
                it[clientId] = "client-1"
                it[clientName] = "Legacy Client"
                it[clientType] = "public"
                it[issuerAccountId] = "account-1"
                it[createdAt] = LocalDateTime.now()
                it[updatedAt] = LocalDateTime.now()
            }
            exec("UPDATE oauth_clients SET redirect_uri = '$redirectUri' WHERE client_id = 'client-1'")
        }
    }

    private fun hasLegacyColumn(): Boolean = transaction {
        currentDialectMetadata.resetCaches()
        currentDialectMetadata.tableColumns(OAuthClients)[OAuthClients].orEmpty()
            .any { it.name.equals("redirect_uri", ignoreCase = true) }
    }

    private fun redirectUrisOf(clientId: String): List<String> = transaction {
        OAuthClientRedirectUris.selectAll()
            .where { OAuthClientRedirectUris.clientId eq clientId }
            .orderBy(OAuthClientRedirectUris.position)
            .map { it[OAuthClientRedirectUris.redirectUri] }
    }

    @Test
    @DisplayName("Copies legacy redirect URI to new table and drops the old column")
    fun migratesLegacyRedirectUri() {
        createLegacySchema("https://example.com/callback")

        DatabaseMigrator.migrate()

        assertEquals(listOf("https://example.com/callback"), redirectUrisOf("client-1"))
        assertFalse(hasLegacyColumn())
    }

    @Test
    @DisplayName("Running migration twice does not duplicate redirect URIs")
    fun migrationIsIdempotent() {
        createLegacySchema("https://example.com/callback")

        DatabaseMigrator.migrate()
        val secondRun = DatabaseMigrator.migrate()

        assertEquals(listOf("https://example.com/callback"), redirectUrisOf("client-1"))
        assertTrue(secondRun.isEmpty())
    }
}
