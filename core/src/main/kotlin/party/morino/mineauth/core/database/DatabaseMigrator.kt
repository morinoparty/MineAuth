package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.vendors.currentDialectMetadata
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import java.sql.SQLException

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
        RevokedGrants,
        ServiceAccountTokens
    )

    /**
     * スキーマを最新の状態にする
     *
     * 1. 存在しないテーブルを作成する
     * 2. 既存の oauth_clients に introspection_allowed カラムを追加する
     * 3. 既存の revoked_tokens に revoked_at_ms カラムを追加する
     * 4. oauth_clients.redirect_uri の値を oauth_client_redirect_uris にコピーする
     * 5. テーブル定義から消えたカラム（oauth_clients.redirect_uri）を削除する
     *
     * コピーと削除を別トランザクションに分けているのは、
     * MySQLのDDLが暗黙的にコミットされるため、コピーの完了を確定させてから削除したいからである
     *
     * @return 実行したマイグレーションの内容（追加したカラムのDDLなど、ログ出力用）
     */
    fun migrate(): List<String> {
        val executed = mutableListOf<String>()

        // 新しいテーブル（oauth_client_redirect_uris など）を作成する
        transaction {
            SchemaUtils.create(*tables)
        }

        // SchemaUtils.create は既存のテーブルにカラムを追加しないため、ここで補う
        executed += addIntrospectionAllowedColumn()
        executed += addRevokedAtEpochMillisColumn()

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
    private fun JdbcTransaction.hasLegacyRedirectUriColumn(): Boolean =
        hasColumn(OAuthClients, LEGACY_REDIRECT_URI_COLUMN)

    /**
     * テーブルの実際のスキーマに指定したカラムが存在するか確認する
     *
     * @param table 確認するテーブル
     * @param columnName カラム名
     * @return 存在する場合true
     */
    private fun JdbcTransaction.hasColumn(table: Table, columnName: String): Boolean {
        // 前回の確認結果がキャッシュされていると、追加や削除の結果が反映されないためリセットする
        currentDialectMetadata.resetCaches()
        val columns = currentDialectMetadata.tableColumns(table)[table].orEmpty()
        return columns.any { it.name.equals(columnName, ignoreCase = true) }
    }

    /**
     * oauth_clients に introspection_allowed カラムが無ければ追加する
     *
     * @return 実行したDDL（追加しなかった場合は空）
     */
    private fun addIntrospectionAllowedColumn(): List<String> =
        addDefinedColumnIfMissing(OAuthClients.introspectionAllowed)

    /**
     * revoked_tokens に revoked_at_ms カラムが無ければ追加する
     * 既存の行はNULLのままにし、値を埋めない。revoked_at から埋めると、
     * タイムゾーン変換でずれた日時をgrace期間の判定に持ち込んでしまうためである
     * （NULLの行の再提示は再利用として扱われる。RevokedTokenRepository.findRevokedAtを参照）
     *
     * @return 実行したDDL（追加しなかった場合は空）
     */
    private fun addRevokedAtEpochMillisColumn(): List<String> =
        addDefinedColumnIfMissing(RevokedTokens.revokedAtEpochMillis)

    /**
     * テーブル定義にあるカラムが実際のスキーマに無ければ追加する
     * DDLはExposedの生成結果を使い、新規作成時のテーブル定義と同じ型とデフォルト値にする
     *
     * @param column 追加するカラム（テーブル定義のもの）
     * @return 実行したDDL（追加しなかった場合は空）
     */
    private fun addDefinedColumnIfMissing(column: Column<*>): List<String> =
        addColumnIfMissing(
            columnExists = { transaction { hasColumn(column.table, column.name) } },
            addColumn = {
                // MySQLのDDLは暗黙的にコミットされるため、他の処理と同じトランザクションに含めない
                transaction { column.createStatement().onEach { exec(it) } }
            }
        )

    /**
     * カラムが無ければ追加する
     *
     * 複数サーバーが同時に起動すると、確認から追加までの間に他のサーバーが先に追加し、ALTERが失敗することがある。
     * その場合は目的を達成できているので成功として扱い、カラムが本当に無いときだけ例外を再送出する
     *
     * @param columnExists 実際のスキーマにカラムが存在するか確認する関数
     * @param addColumn カラムを追加し、実行したDDLを返す関数
     * @return 実行したDDL（追加しなかった場合は空）
     */
    internal fun addColumnIfMissing(columnExists: () -> Boolean, addColumn: () -> List<String>): List<String> {
        if (columnExists()) return emptyList()
        return try {
            addColumn()
        } catch (e: SQLException) {
            if (columnExists()) emptyList() else throw e
        }
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
