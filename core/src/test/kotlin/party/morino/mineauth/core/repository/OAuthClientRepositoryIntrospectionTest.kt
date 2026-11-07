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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OAuthClientRepositoryのトークンイントロスペクション権限のテスト
 * 権限を持てるのがConfidentialクライアントだけであることを確認する（Issue #480）
 */
class OAuthClientRepositoryIntrospectionTest {

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
     * 指定した種別のクライアントを作成する
     */
    private suspend fun createClient(clientType: ClientType, introspectionAllowed: Boolean = false): OAuthClientData {
        val account = AccountRepository.create(AccountType.PLAYER, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        return OAuthClientRepository.create(
            "Test Client",
            clientType,
            listOf("https://example.com/callback"),
            account.accountId,
            introspectionAllowed
        ).getOrElse { error("Failed to create client: $it") }.client
    }

    /**
     * データベースに保存されている権限の状態を読む
     */
    private suspend fun storedIntrospectionAllowed(clientId: String): Boolean =
        OAuthClientRepository.findById(clientId).getOrElse { error("Not found: $it") }.introspectionAllowed

    @Test
    @DisplayName("A client has no permission unless it is requested")
    fun clientHasNoPermissionByDefault() = runTest {
        val client = createClient(ClientType.CONFIDENTIAL)

        assertFalse(client.introspectionAllowed)
        assertFalse(storedIntrospectionAllowed(client.clientId))
    }

    @Test
    @DisplayName("Create applies the permission to confidential clients only")
    fun createAppliesThePermissionToConfidentialClientsOnly() = runTest {
        val confidential = createClient(ClientType.CONFIDENTIAL, introspectionAllowed = true)
        val public = createClient(ClientType.PUBLIC, introspectionAllowed = true)

        assertTrue(confidential.introspectionAllowed)
        assertTrue(storedIntrospectionAllowed(confidential.clientId))
        assertFalse(public.introspectionAllowed)
        assertFalse(storedIntrospectionAllowed(public.clientId))
    }

    @Test
    @DisplayName("The permission of a confidential client can be granted and withdrawn")
    fun permissionOfConfidentialClientCanBeChanged() = runTest {
        val client = createClient(ClientType.CONFIDENTIAL)

        val granted = OAuthClientRepository.setIntrospectionAllowed(client.clientId, allowed = true)
            .getOrElse { error("Failed to grant: $it") }
        assertTrue(granted.introspectionAllowed)
        assertTrue(storedIntrospectionAllowed(client.clientId))

        val withdrawn = OAuthClientRepository.setIntrospectionAllowed(client.clientId, allowed = false)
            .getOrElse { error("Failed to withdraw: $it") }
        assertFalse(withdrawn.introspectionAllowed)
        assertFalse(storedIntrospectionAllowed(client.clientId))
    }

    @Test
    @DisplayName("A public client cannot hold the permission")
    fun publicClientCannotHoldThePermission() = runTest {
        val client = createClient(ClientType.PUBLIC)

        val result = OAuthClientRepository.setIntrospectionAllowed(client.clientId, allowed = true)

        assertEquals(OAuthClientError.InvalidCredentials, result.leftOrNull())
        assertFalse(storedIntrospectionAllowed(client.clientId))
    }

    @Test
    @DisplayName("An unknown client is reported as not found")
    fun unknownClientIsNotFound() = runTest {
        val result = OAuthClientRepository.setIntrospectionAllowed("unknown-client", allowed = true)

        assertEquals(OAuthClientError.NotFound, result.leftOrNull())
    }
}
