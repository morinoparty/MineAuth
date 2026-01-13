package party.morino.mineauth.core.database

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
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

    // リダイレクトURI（正規表現パターンをサポート）
    val redirectUri = varchar("redirect_uri", 2048)

    // 発行者アカウントID（Accountsテーブルへの外部キー）
    val issuerAccountId = varchar("issuer_account_id", 36)
        .references(Accounts.accountId, onDelete = ReferenceOption.CASCADE)

    // 作成日時
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }

    // 更新日時
    val updatedAt = datetime("updated_at").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(clientId)
}
