package party.morino.mineauth.core.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.datetime
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

    // 失効日時（サーバーのタイムゾーン）
    // 運用者がテーブルを読むための値で、判定には使わない。他の日時カラムと同じくサーバーのタイムゾーンで記録する
    val revokedAt = datetime("revoked_at").clientDefault { LocalDateTime.now() }

    // 失効した時刻（エポックミリ秒）
    // リフレッシュトークンの再提示がgrace期間内かの判定に使う唯一の値。
    // 日時型はMySQLなどでJVMやセッションのタイムゾーンを通して変換され、夏時間の切り替わりで値がずれることがあるため、
    // タイムゾーンを持たない数値で記録する
    // このカラムを追加する前のデータや、追加前のバージョンが書いた行ではNULLになる
    val revokedAtEpochMillis = long("revoked_at_ms").nullable()

    // トークンの有効期限（定期クリーンアップ用、サーバーのタイムゾーン）
    // 有効期限を過ぎたレコードは安全に削除可能
    val expiresAt = datetime("expires_at")

    override val primaryKey = PrimaryKey(tokenId)
}
