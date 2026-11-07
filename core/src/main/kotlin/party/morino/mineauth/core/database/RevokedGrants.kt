package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime
import java.time.LocalDateTime

/**
 * 失効グラントテーブル
 * 認可コードのリプレイやリフレッシュトークンの再利用を検知したときに、同じグラントのトークンをまとめて失効させる
 *
 * JWTはステートレスなため、グラントの失効状態もブラックリストとして管理する
 */
object RevokedGrants : Table("revoked_grants") {
    // グラントID（grant_id claim）- UUIDv4形式
    val grantId = varchar("grant_id", 36)

    // 失効対象のクライアントID
    val clientId = varchar("client_id", 36)

    // 失効日時
    val revokedAt = datetime("revoked_at").clientDefault { LocalDateTime.now() }

    // このレコードを削除してよくなる日時（定期クリーンアップ用）
    // 提示されたトークンの有効期限からは求めない（RevokedGrantRepository.revokeを参照）
    val expiresAt = datetime("expires_at")

    override val primaryKey = PrimaryKey(grantId)
}
