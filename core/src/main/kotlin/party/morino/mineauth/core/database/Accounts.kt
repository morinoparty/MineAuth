package party.morino.mineauth.core.database

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.datetime
import java.time.LocalDateTime

/**
 * アカウントテーブル
 * プレイヤーアカウントとサービスアカウント（ロボットアカウント）を管理する
 */
object Accounts : Table("accounts") {
    // UUIDv7形式のアカウントID（時間ソート可能）
    val accountId = varchar("account_id", 36)

    // アカウント種別: "player" または "service"
    val accountType = varchar("account_type", 20)

    // 識別子: プレイヤーの場合はMinecraft UUID、サービスの場合はサービス名
    val identifier = varchar("identifier", 64)

    // 作成日時
    val createdAt = datetime("created_at").clientDefault { LocalDateTime.now() }

    override val primaryKey = PrimaryKey(accountId)
}
