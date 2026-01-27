package party.morino.mineauth.core.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.DatabaseConfig
import party.morino.mineauth.core.file.data.MineAuthConfig
import java.io.File

/**
 * データベース接続管理クラス
 * 設定に基づいてSQLiteまたはMySQLに接続する
 */
class DatabaseConnector : KoinComponent {
    private val plugin: MineAuth by inject()
    private val config: MineAuthConfig by inject()

    // HikariCPのデータソース（MySQL使用時のみ）
    private var dataSource: HikariDataSource? = null

    /**
     * データベースに接続し、テーブルを作成する
     */
    fun connect() {
        when (val dbConfig = config.database) {
            is DatabaseConfig.SQLite -> connectSQLite(dbConfig)
            is DatabaseConfig.MySQL -> connectMySQL(dbConfig)
        }

        // テーブル作成（Accountsを先に作成 - OAuthClientsが参照するため）
        // RevokedTokensはトークン失効管理用（RFC 7009準拠）
        transaction {
            SchemaUtils.create(UserAuthData, Accounts, OAuthClients, RevokedTokens)
        }

        plugin.logger.info("Database connected: ${config.database::class.simpleName}")
    }

    /**
     * SQLite接続
     * 従来通りの直接接続を使用
     */
    private fun connectSQLite(config: DatabaseConfig.SQLite) {
        val dbPath = "${plugin.dataFolder}${File.separator}${config.filename}"
        Database.connect(
            url = "jdbc:sqlite:$dbPath",
            driver = "org.sqlite.JDBC"
        )
    }

    /**
     * MySQL接続
     * HikariCPを使用したコネクションプール管理
     */
    private fun connectMySQL(config: DatabaseConfig.MySQL) {
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

        dataSource = HikariDataSource(hikariConfig)
        Database.connect(dataSource!!)
    }

    /**
     * データベース切断
     * HikariCPのデータソースをクローズする
     */
    fun disconnect() {
        dataSource?.close()
        dataSource = null
        plugin.logger.info("Database disconnected")
    }
}
