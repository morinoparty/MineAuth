package party.morino.mineauth.addons.votingplugin.routes

import com.bencodez.votingplugin.VotingPluginHooks
import com.bencodez.votingplugin.topvoter.TopVoter
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.votingplugin.data.PlayerVoteSiteStatus
import party.morino.mineauth.addons.votingplugin.data.PlayerVoteSitesResponse
import party.morino.mineauth.addons.votingplugin.data.VoteSiteResponse
import party.morino.mineauth.addons.votingplugin.data.VoteSitesResponse
import party.morino.mineauth.addons.votingplugin.data.VoteStatsResponse
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.TargetPlayer

/**
 * VotingPluginの投票データを提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class VotingPluginHandler : KoinComponent {
    private val hooks: VotingPluginHooks by inject()

    /**
     * プレイヤーの投票統計を取得する
     * GET /stats/{player}
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return 投票統計を含むレスポンス
     */
    @GetMapping("/stats/{player}")
    suspend fun getVoteStats(@TargetPlayer player: OfflinePlayer): VoteStatsResponse {
        val user = hooks.userManager.getVotingPluginUser(player)

        return VoteStatsResponse(
            // 各期間の投票数を取得
            allTimeTotal = user.getTotal(TopVoter.AllTime),
            monthlyTotal = user.getTotal(TopVoter.Monthly),
            weeklyTotal = user.getTotal(TopVoter.Weekly),
            dailyTotal = user.getTotal(TopVoter.Daily),
            // ポイントを取得
            points = user.points,
            // 投票ストリークを取得
            dayVoteStreak = user.dayVoteStreak,
            weekVoteStreak = user.weekVoteStreak,
            monthVoteStreak = user.monthVoteStreak,
            // 過去最高の投票ストリークを取得
            bestDayVoteStreak = user.bestDayVoteStreak,
            bestWeekVoteStreak = user.bestWeekVoteStreak,
            bestMonthVoteStreak = user.bestMonthVoteStreak,
            // 最終投票時刻を取得
            lastVoteTime = user.lastVoteTime,
        )
    }

    /**
     * 利用可能な投票サイト一覧を取得する
     * GET /sites
     *
     * @return 投票サイト一覧のレスポンス
     */
    @GetMapping("/sites")
    suspend fun getVoteSites(): VoteSitesResponse {
        val plugin = hooks.mainClass
        val allSites = plugin.voteSites

        // 各サイトの情報をレスポンスに変換
        val sites = allSites.map { site ->
            VoteSiteResponse(
                name = site.key,
                url = site.voteURL,
                enabled = site.isEnabled,
            )
        }

        return VoteSitesResponse(sites = sites)
    }

    /**
     * プレイヤーの各サイトごとの投票状況を取得する
     * GET /sites/{player}
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return プレイヤーの投票サイト状況一覧のレスポンス
     */
    @GetMapping("/sites/{player}")
    suspend fun getPlayerVoteSites(@TargetPlayer player: OfflinePlayer): PlayerVoteSitesResponse {
        val user = hooks.userManager.getVotingPluginUser(player)
        val plugin = hooks.mainClass
        val allSites = plugin.voteSites

        // 各サイトごとの投票状況を取得
        val sites = allSites.map { site ->
            PlayerVoteSiteStatus(
                siteName = site.key,
                canVote = user.canVoteSite(site),
                lastVoteTime = user.getTime(site),
            )
        }

        // 投票済みサイト数を計算
        val votedCount = sites.count { !it.canVote }

        return PlayerVoteSitesResponse(
            sites = sites,
            votedSites = votedCount,
            totalSites = allSites.size,
            canVoteAll = user.canVoteAll(),
        )
    }
}
