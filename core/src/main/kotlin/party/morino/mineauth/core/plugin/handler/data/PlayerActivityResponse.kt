package party.morino.mineauth.core.plugin.handler.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.UUID

/**
 * プレイヤーの最終ログイン・プレイ時間などの活動情報
 * GET /api/v1/plugins/mineauth/players/{player} で返却される
 *
 * 日時はすべてISO-8601形式（UTC、例: 2026-09-11T12:34:56.789Z）の文字列。
 * Bukkitが「記録なし」を意味する0を返した場合はnullとする。
 */
@Serializable
data class PlayerActivityResponse(
    // プレイヤーのUUID
    val uuid: @Serializable(with = UUIDSerializer::class) UUID,
    // プレイヤー名（サーバーのキャッシュに存在しない場合はnull）
    val name: String?,
    // 現在オンラインかどうか
    val online: Boolean,
    // 初めてサーバーに参加した日時
    val firstPlayed: String?,
    // 最後にログインした日時
    val lastLogin: String?,
    // 最後にサーバーにいた日時（オンライン中は現在時刻）
    // Paperで非推奨となったOfflinePlayer.getLastPlayed()の代替としてgetLastSeen()から取得する
    val lastPlayed: String?,
    // 累計プレイ時間（秒）。Statistic.PLAY_ONE_MINUTE（tick単位）から換算する
    val playtimeSeconds: Long,
)
