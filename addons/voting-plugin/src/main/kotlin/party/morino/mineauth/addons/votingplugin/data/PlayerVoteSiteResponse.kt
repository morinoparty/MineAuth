package party.morino.mineauth.addons.votingplugin.data

import kotlinx.serialization.Serializable

/**
 * プレイヤーの個別サイトごとの投票状況
 *
 * @property siteName サイト名
 * @property canVote 投票可能かどうか
 * @property lastVoteTime 最終投票時刻（エポックミリ秒）
 */
@Serializable
data class PlayerVoteSiteStatus(
    val siteName: String,
    val canVote: Boolean,
    val lastVoteTime: Long,
)

/**
 * プレイヤーの投票サイト状況一覧レスポンス
 *
 * @property sites 各サイトの投票状況リスト
 * @property votedSites 投票済みサイト数
 * @property totalSites 全サイト数
 * @property canVoteAll 全サイトに投票可能かどうか
 */
@Serializable
data class PlayerVoteSitesResponse(
    val sites: List<PlayerVoteSiteStatus>,
    val votedSites: Int,
    val totalSites: Int,
    val canVoteAll: Boolean,
)
