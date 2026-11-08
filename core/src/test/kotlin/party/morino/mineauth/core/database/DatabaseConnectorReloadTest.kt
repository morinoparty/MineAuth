package party.morino.mineauth.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.DatabaseConfig
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.load.config.ConfigLoader
import party.morino.mineauth.core.utils.json
import java.io.File
import java.sql.SQLException
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /mineauth reload での接続の入れ替えのテスト
 * 新しい設定で接続し直し、失敗したときは古い接続が使えるまま残ることを確認する
 *
 * MySQLサーバーなしでプールの入れ替えを確かめるため、MySQL設定のdatabaseをファイル名としたSQLiteのプールを作る
 */
class DatabaseConnectorReloadTest {
    @TempDir
    lateinit var dataFolder: File

    // 作成したプール（作成順）
    private val pools = mutableListOf<HikariDataSource>()

    private val connector = DatabaseConnector { config ->
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:sqlite:${dataFolder.resolve(config.database)}"
                driverClassName = "org.sqlite.JDBC"
                maximumPoolSize = 2
                // 接続はできるがスキーマを作れないデータベースを再現する
                if (config.database == READ_ONLY_DATABASE) connectionInitSql = "PRAGMA query_only = ON"
            }
        ).also { pools += it }
    }

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("DatabaseConnectorReloadTest")
        every { plugin.dataFolder } returns dataFolder
        startKoin {
            modules(module { single { plugin } })
        }
    }

    @AfterEach
    fun tearDown() {
        connector.disconnect()
        pools.forEach { it.close() }
        stopKoin()
    }

    /**
     * config.jsonを書き込み、/mineauth reload と同じくConfigLoaderで読み直して接続する
     */
    private fun reloadWith(database: DatabaseConfig) {
        val config = MineAuthConfig(database = database, password = PasswordConfig(pepper = "pepper"))
        dataFolder.resolve("config.json").writeText(json.encodeToString(config))
        ConfigLoader().load()
        connector.connect()
    }

    /**
     * 既定の接続で実際にクエリを実行し、接続先のファイル名を返す
     */
    private fun connectedFileName(): String = transaction {
        exec("PRAGMA database_list") { resultSet ->
            resultSet.next()
            File(resultSet.getString("file")).name
        }
    }.orEmpty()

    @Test
    @DisplayName("Closes the new pool and keeps the old one when migration fails")
    fun closesNewPoolWhenMigrationFails() {
        reloadWith(DatabaseConfig.MySQL(database = "first.db"))

        assertThrows<SQLException> { reloadWith(DatabaseConfig.MySQL(database = READ_ONLY_DATABASE)) }

        assertTrue(pools[1].isClosed)
        assertFalse(pools[0].isClosed)
        assertEquals("first.db", connectedFileName())
    }

    private companion object {
        // 書き込みできないデータベースを表すMySQL設定のdatabase名
        const val READ_ONLY_DATABASE = "read-only.db"
    }
}
