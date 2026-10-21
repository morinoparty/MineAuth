package party.morino.mineauth.core.repository

import arrow.core.getOrElse
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.database.DatabaseMigrator
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals

/**
 * OAuthClientRepositoryのリダイレクトURI保存のテスト
 * 複数URIが登録順に保存・取得され、更新で置き換わることを確認する
 */
class OAuthClientRepositoryRedirectUriTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // テストごとに新しいSQLiteファイルへ接続し、最新のスキーマを作成する
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()
    }

    /**
     * 指定したリダイレクトURIを持つPublicクライアントを作成する
     */
    private suspend fun createClient(redirectUris: List<String>): OAuthClientData {
        val account = AccountRepository.create(AccountType.PLAYER, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        return OAuthClientRepository.create("Test Client", ClientType.PUBLIC, redirectUris, account.accountId)
            .getOrElse { error("Failed to create client: $it") }
            .client
    }

    @Test
    @DisplayName("Stores multiple redirect URIs in registration order")
    fun storesMultipleRedirectUrisInOrder() = runTest {
        val uris = listOf("https://example.com/callback", "http://localhost:3000/callback")
        val client = createClient(uris)

        val found = OAuthClientRepository.findById(client.clientId).getOrElse { error("Not found: $it") }
        val listed = OAuthClientRepository.findAll().getOrElse { error("Failed to list: $it") }

        assertEquals(uris, found.redirectUris)
        assertEquals(uris, listed.single().redirectUris)
    }

    @Test
    @DisplayName("Update replaces all redirect URIs")
    fun updateReplacesRedirectUris() = runTest {
        val client = createClient(listOf("https://example.com/callback", "http://localhost:3000/callback"))

        val updated = OAuthClientRepository.update(client.clientId, redirectUris = listOf("https://new.example.com/cb"))
            .getOrElse { error("Failed to update: $it") }

        assertEquals(listOf("https://new.example.com/cb"), updated.redirectUris)
    }
}
