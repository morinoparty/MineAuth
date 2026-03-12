package party.morino.mineauth.addons.votingplugin.data

import kotlinx.serialization.Serializable

/**
 * 投票サイト情報
 *
 * @property name サイト名
 * @property url 投票URL
 * @property enabled サイトが有効かどうか
 */
@Serializable
data class VoteSiteResponse(
    val name: String,
    val url: String,
    val enabled: Boolean,
)

/**
 * 投票サイト一覧レスポンス
 *
 * @property sites 投票サイトのリスト
 */
@Serializable
data class VoteSitesResponse(
    val sites: List<VoteSiteResponse>,
)
