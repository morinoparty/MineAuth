package party.morino.mineauth.core.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * サービスアカウントトークンテーブル
 * 発行されたサービスアカウントトークンのメタデータを管理する
 */
object ServiceAccountTokens : Table("service_account_tokens") {
    // トークンのJWT ID（jti claim）
    val tokenId = varchar("token_id", 36)

    // アカウントID（Accounts.accountIdへの参照）
    val accountId = varchar("account_id", 36).references(Accounts.accountId)

    // トークンハッシュ（SHA-256、トークン漏洩時の検証用）
    val tokenHash = varchar("token_hash", 64)

    // 作成者のプレイヤーUUID
    val createdBy = varchar("created_by", 36)

    // 作成日時
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }

    // 最終使用日時（null = 未使用）
    val lastUsedAt = datetime("last_used_at").nullable()

    // 失効フラグ
    val revoked = bool("revoked").default(false)

    override val primaryKey = PrimaryKey(tokenId)
}
