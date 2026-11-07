package party.morino.mineauth.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.DatabaseConfig
import party.morino.mineauth.core.file.data.MineAuthConfig
import java.io.File

/**
 * データベース接続管理クラス
 * 設定に基づいてSQLiteまたはMySQLに接続する
 *
 * /mineauth reload のたびに最新の設定で接続し直す。
 * 新しい接続でマイグレーションまで終えてから古い接続を閉じるため、新しいデータベースを使えないときは古い接続のまま動き続ける。
 *
 * @param createDataSource MySQLのコネクションプールを作る処理（テストではMySQLなしでプールの入れ替えを確かめるために差し替える）
 */
class DatabaseConnector(
    private val createDataSource: (DatabaseConfig.MySQL) -> HikariDataSource = ::createMySQLDataSource
) : KoinComponent {
    private val plugin: MineAuth by inject()

    // 現在の接続を閉じる処理（未接続の場合はnull）
    private var closeCurrentConnection: (() -> Unit)? = null

    /**
     * データベースに接続し、テーブルを作成する
     * 失敗した場合は例外を投げ、それまでの接続を残す
     */
    fun connect() {
        // 設定は /mineauth reload で置き換わるため、保持せず接続のたびに取得する
        val dbConfig = get<MineAuthConfig>().database
        val closeNewConnection = when (dbConfig) {
            is DatabaseConfig.SQLite -> connectSQLite(dbConfig)
            is DatabaseConfig.MySQL -> connectMySQL(dbConfig)
        }

        // テーブル作成と、旧バージョンのスキーマからのマイグレーション
        // SQLiteはここで初めて実際に接続するため、到達できないデータベースもこの時点で失敗する
        val migrations = try {
            DatabaseMigrator.migrate()
        } catch (e: Exception) {
            // 新しい接続の登録を解除すると、Exposedの既定の接続は古い接続に戻る
            closeNewConnection()
            throw e
        }
        migrations.forEach { plugin.logger.info("Database migration: $it") }

        // 新しい接続で動くことを確かめたので、古い接続を閉じる
        val closePreviousConnection = closeCurrentConnection
        closeCurrentConnection = closeNewConnection
        closePreviousConnection?.invoke()

        plugin.logger.info("Database connected: ${dbConfig::class.simpleName}")
    }

    /**
     * SQLite接続
     * 従来通りの直接接続を使用
     *
     * @return この接続を閉じる処理
     */
    private fun connectSQLite(config: DatabaseConfig.SQLite): () -> Unit {
        val dbPath = "${plugin.dataFolder}${File.separator}${config.filename}"
        val database = Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )
        return { TransactionManager.closeAndUnregister(database) }
    }

    /**
     * MySQL接続
     * HikariCPを使用したコネクションプール管理
     *
     * @return この接続を閉じる処理
     */
    private fun connectMySQL(config: DatabaseConfig.MySQL): () -> Unit {
        // 到達できない場合はプールの生成で例外になるので、既定の接続を切り替える前に失敗する
        val dataSource = createDataSource(config)
        val database = try {
            Database.connect(dataSource)
        } catch (e: Exception) {
            dataSource.close()
            throw e
        }
        return {
            TransactionManager.closeAndUnregister(database)
            dataSource.close()
        }
    }

    /**
     * データベース切断
     * Exposedへの登録を解除し、HikariCPのデータソースをクローズする
     */
    fun disconnect() {
        closeCurrentConnection?.invoke()
        closeCurrentConnection = null
        plugin.logger.info("Database disconnected")
    }
}

/**
 * MySQLのコネクションプールを作る
 * 生成時に接続を試みるため、到達できないデータベースでは例外になる
 *
 * @param config MySQL設定
 * @return コネクションプール
 */
private fun createMySQLDataSource(config: DatabaseConfig.MySQL): HikariDataSource {
    val hikariConfig = HikariConfig().apply {
        // JDBC URL構築（プロパティをクエリパラメータとして追加）
        val props = config.properties.entries.joinToString("&") { "${it.key}=${it.value}" }
        jdbcUrl = "jdbc:mysql://${config.host}:${config.port}/${config.database}?$props"
        driverClassName = "com.mysql.cj.jdbc.Driver"
        username = config.username
        password = config.password

        // コネクションプール設定
        maximumPoolSize = 10
        minimumIdle = 2
        idleTimeout = 300000      // 5分
        connectionTimeout = 10000  // 10秒
        maxLifetime = 1800000     // 30分
    }

    return HikariDataSource(hikariConfig)
}
