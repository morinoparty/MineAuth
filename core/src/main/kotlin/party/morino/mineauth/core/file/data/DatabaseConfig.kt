package party.morino.mineauth.core.file.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * データベース設定
 * SQLiteとMySQLをsealed classで表現し、型安全に設定を管理する
 */
@Serializable
sealed class DatabaseConfig {
    /**
     * SQLite設定（デフォルト）
     * @property filename データベースファイル名（pluginディレクトリ相対）
     */
    @Serializable
    @SerialName("sqlite")
    data class SQLite(
        // データベースファイル名
        val filename: String = "MineAuth.db"
    ) : DatabaseConfig()

    /**
     * MySQL設定
     * @property host MySQLホスト
     * @property port MySQLポート
     * @property database データベース名
     * @property username ユーザー名
     * @property password パスワード
     * @property properties JDBC接続プロパティ
     */
    @Serializable
    @SerialName("mysql")
    data class MySQL(
        // MySQLホスト
        val host: String = "localhost",

        // MySQLポート
        val port: Int = 3306,

        // データベース名
        val database: String = "mineauth",

        // ユーザー名
        val username: String = "root",

        // パスワード
        val password: String = "",

        // JDBC接続プロパティ
        val properties: Map<String, String> = mapOf(
            "useSSL" to "false",
            "autoReconnect" to "true"
        )
    ) : DatabaseConfig()
}
