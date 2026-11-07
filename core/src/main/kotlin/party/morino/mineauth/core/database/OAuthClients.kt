package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime

/**
 * OAuthクライアントテーブル
 * OAuth2/OIDCクライアントアプリケーションの情報を管理する
 */
object OAuthClients : Table("oauth_clients") {
    // UUIDv7形式のクライアントID（時間ソート可能）
    val clientId = varchar("client_id", 36)

    // クライアント名（表示用）
    val clientName = varchar("client_name", 255)

    // クライアント種別: "public" または "confidential"
    val clientType = varchar("client_type", 20)

    // クライアントシークレットのArgon2idハッシュ（Publicクライアントの場合はNULL）
    val clientSecretHash = varchar("client_secret_hash", 255).nullable()

    // リダイレクトURIは複数登録できるよう OAuthClientRedirectUris テーブルで管理する

    // 発行者アカウントID（Accountsテーブルへの外部キー）
    val issuerAccountId = varchar("issuer_account_id", 36)
        .references(Accounts.accountId, onDelete = ReferenceOption.CASCADE)

    // トークンイントロスペクション（RFC 7662）の利用権限。サーバー管理者が許可したConfidentialクライアントのみtrueになる
    // 既存のテーブルには DatabaseMigrator が追加するため、DB側のデフォルト値が必要（clientDefaultでは既存行を埋められない）
    val introspectionAllowed = bool("introspection_allowed").default(false)

    // 作成日時
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }

    // 更新日時
    val updatedAt = datetime("updated_at").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(clientId)
}
