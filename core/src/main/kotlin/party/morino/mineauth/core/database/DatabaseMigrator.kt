package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/**
 * データベースのスキーマ作成とマイグレーションを行う
 *
 * 起動のたびに実行しても結果が変わらない（冪等）ように作ってあるため、
 * バージョン管理テーブルは持たず、実際のスキーマの状態を見て必要な処理だけを行う
 */
object DatabaseMigrator {
    // 旧バージョンで oauth_clients に存在したリダイレクトURIのカラム名
    private const val LEGACY_REDIRECT_URI_COLUMN = "redirect_uri"

    // 作成対象のテーブル（外部キーの参照先を先に並べる）
    private val tables: Array<Table> = arrayOf(
        UserAuthData,
        Accounts,
        OAuthClients,
        OAuthClientRedirectUris,
        RevokedTokens,
        ServiceAccountTokens
    )

    /**
     * スキーマを最新の状態にする
     *
     * 1. 存在しないテーブルを作成する
     * 2. oauth_clients.redirect_uri の値を oauth_client_redirect_uris にコピーする
     * 3. テーブル定義から消えたカラム（oauth_clients.redirect_uri）を削除する
     *
     * コピーと削除を別トランザクションに分けているのは、
     * MySQLのDDLが暗黙的にコミットされるため、コピーの完了を確定させてから削除したいからである
     *
     * @return 実行したマイグレーションの内容（ログ出力用）
     */
    fun migrate(): List<String> {
        val executed = mutableListOf<String>()

        // 新しいテーブル（oauth_client_redirect_uris など）を作成する
        transaction {
            SchemaUtils.create(*tables)
        }

        // 旧カラムが残っている場合のみ、データをコピーしてからカラムを削除する
        val hasLegacyColumn = transaction { hasLegacyRedirectUriColumn() }
        if (hasLegacyColumn) {
            val copied = transaction { copyLegacyRedirectUris() }
            executed += "Copied $copied redirect URI(s) from oauth_clients.$LEGACY_REDIRECT_URI_COLUMN"

            executed += transaction { dropUnmappedColumns() }
        }

        return executed
    }

    /**
     * oauth_clients に旧カラム redirect_uri が存在するか確認する
     */
    private fun JdbcTransaction.hasLegacyRedirectUriColumn(): Boolean {
        // 前回の確認結果がキャッシュされていると、削除後も残っているように見えるためリセットする
        currentDialectMetadata.resetCaches()
        val columns = currentDialectMetadata.tableColumns(OAuthClients)[OAuthClients].orEmpty()
        return columns.any { it.name.equals(LEGACY_REDIRECT_URI_COLUMN, ignoreCase = true) }
    }

    /**
     * 旧カラムのリダイレクトURIを新テーブルの先頭（position = 0）にコピーする
     *
     * 旧カラムはテーブル定義から削除済みでDSLから参照できないため、SQLで直接読み出す
     * insertIgnore と (client_id, position) のユニーク制約により、
     * 複数サーバーが同時に起動して二重にコピーしても1件しか登録されない
     *
     * @return 新たにコピーした件数
     */
    private fun JdbcTransaction.copyLegacyRedirectUris(): Int {
        val legacyRows = exec(
            "SELECT client_id, $LEGACY_REDIRECT_URI_COLUMN FROM ${OAuthClients.tableName}"
        ) { rs ->
            buildList {
                while (rs.next()) {
                    val uri = rs.getString(LEGACY_REDIRECT_URI_COLUMN)
                    // NULLや空文字は移行しても認可に使えないため除外する
                    if (!uri.isNullOrBlank()) add(rs.getString("client_id") to uri)
                }
            }
        }.orEmpty()

        return legacyRows.sumOf { (clientId, uri) ->
            OAuthClientRedirectUris.insertIgnore {
                it[OAuthClientRedirectUris.clientId] = clientId
                it[OAuthClientRedirectUris.position] = 0
                it[OAuthClientRedirectUris.redirectUri] = uri
            }.insertedCount
        }
    }

    /**
     * テーブル定義に存在しないカラムを削除する
     * exposed-migration がDBの実際のスキーマとテーブル定義を比較してDDLを生成する
     *
     * @return 実行したDDL
     */
    private fun JdbcTransaction.dropUnmappedColumns(): List<String> {
        val statements = MigrationUtils.dropUnmappedColumnsStatements(OAuthClients, withLogs = false)
        statements.forEach { exec(it) }
        return statements
    }
}
