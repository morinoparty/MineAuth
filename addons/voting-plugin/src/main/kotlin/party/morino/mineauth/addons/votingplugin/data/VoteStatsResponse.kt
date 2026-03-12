package party.morino.mineauth.addons.votingplugin.data

import kotlinx.serialization.Serializable

/**
 * プレイヤーの投票統計レスポンス
 *
 * @property allTimeTotal 全期間の総投票数
 * @property monthlyTotal 月間投票数
 * @property weeklyTotal 週間投票数
 * @property dailyTotal 日間投票数
 * @property points 現在のポイント
 * @property dayVoteStreak 日間投票ストリーク
 * @property weekVoteStreak 週間投票ストリーク
 * @property monthVoteStreak 月間投票ストリーク
 * @property bestDayVoteStreak 過去最高の日間投票ストリーク
 * @property bestWeekVoteStreak 過去最高の週間投票ストリーク
 * @property bestMonthVoteStreak 過去最高の月間投票ストリーク
 * @property lastVoteTime 最終投票時刻（エポックミリ秒）
 */
@Serializable
data class VoteStatsResponse(
    val allTimeTotal: Int,
    val monthlyTotal: Int,
    val weeklyTotal: Int,
    val dailyTotal: Int,
    val points: Int,
    val dayVoteStreak: Int,
    val weekVoteStreak: Int,
    val monthVoteStreak: Int,
    val bestDayVoteStreak: Int,
    val bestWeekVoteStreak: Int,
    val bestMonthVoteStreak: Int,
    val lastVoteTime: Long,
)
