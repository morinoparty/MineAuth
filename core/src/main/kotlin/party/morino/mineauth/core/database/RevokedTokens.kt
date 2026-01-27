package party.morino.mineauth.core.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * 失効トークンテーブル
 * RFC 7009 Token Revocationに基づき、失効したトークンのJWT ID（jti）を管理する
 *
 * JWTトークンはステートレスな設計のため、失効状態をブラックリストとして管理する必要がある
 */
object RevokedTokens : Table("revoked_tokens") {
    // トークンのJWT ID（jti claim）- UUIDv4形式
    val tokenId = varchar("token_id", 36)

    // トークン種別: "access_token" または "refresh_token"
    val tokenType = varchar("token_type", 20)

    // 失効対象のクライアントID
    val clientId = varchar("client_id", 36)

    // 失効日時
    val revokedAt = datetime("revoked_at").clientDefault { LocalDateTime.now() }

    // トークンの有効期限（定期クリーンアップ用）
    // 有効期限を過ぎたレコードは安全に削除可能
    val expiresAt = datetime("expires_at")

    override val primaryKey = PrimaryKey(tokenId)
}
