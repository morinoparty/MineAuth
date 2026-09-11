package party.morino.mineauth.core.plugin.handler

import kotlinx.coroutines.withContext
import org.bukkit.OfflinePlayer
import org.bukkit.Statistic
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.PlayerParam
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import party.morino.mineauth.core.plugin.handler.data.PlayerActivityResponse
import party.morino.mineauth.core.utils.coroutines.minecraft
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/**
 * MineAuth本体が提供するプレイヤー活動情報のハンドラー
 * アドオンと同じ登録機構（MineAuthApi.register）で "mineauth" 名前空間に登録される
 *
 * 外部プラグインに依存しない情報（最終ログイン・プレイ時間など）はアドオンに切り出さず、
 * ここでまとめて提供する。
 */
class PlayerActivityHandler {

    /**
     * プレイヤーの最終ログイン・プレイ時間を取得する
     * GET /players/{player}
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return 活動情報。一度もサーバーに参加していないプレイヤーは404
     */
    @Get("/players/{player}")
    @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun getPlayerActivity(@PlayerParam("player") player: OfflinePlayer): PlayerActivityResponse {
        // CraftOfflinePlayerはオフラインプレイヤーのplayerdata/statsファイルを読むため、
        // Bukkitへのアクセスはメインスレッドでまとめて行い、整形はその外で行う
        val snapshot = withContext(Dispatchers.minecraft) {
            // hasPlayedBefore()は初回参加中のプレイヤー（オンライン）に対してもfalseを返すため、
            // オンラインでもなく過去の参加記録もない場合だけ404にする
            if (!player.isOnline && !player.hasPlayedBefore()) {
                throw HttpError(HttpStatus.NOT_FOUND, "Player has never joined this server")
            }
            ActivitySnapshot(
                online = player.isOnline,
                firstPlayed = player.firstPlayed,
                lastLogin = player.lastLogin,
                lastSeen = player.lastSeen,
                // PLAY_ONE_MINUTEは名前に反してtick単位で記録されている
                playtimeTicks = player.getStatistic(Statistic.PLAY_ONE_MINUTE),
            )
        }

        return PlayerActivityResponse(
            uuid = player.uniqueId,
            name = player.name,
            online = snapshot.online,
            firstPlayed = snapshot.firstPlayed.toIsoOrNull(),
            lastLogin = snapshot.lastLogin.toIsoOrNull(),
            lastPlayed = snapshot.lastSeen.toIsoOrNull(),
            playtimeSeconds = snapshot.playtimeTicks / TICKS_PER_SECOND,
        )
    }

    /**
     * メインスレッドで取得したBukkitの生の値
     * withContextブロックを最小限に保つための一時的な入れ物
     */
    private data class ActivitySnapshot(
        val online: Boolean,
        val firstPlayed: Long,
        val lastLogin: Long,
        val lastSeen: Long,
        val playtimeTicks: Int,
    )

    companion object {
        // Minecraftのtick数（20 tick = 1秒）
        private const val TICKS_PER_SECOND = 20L

        /**
         * エポックミリ秒をISO-8601（UTC）文字列に変換する
         * Bukkitは「記録なし」を0で表すため、0以下はnullとして扱う
         */
        internal fun Long.toIsoOrNull(): String? =
            if (this <= 0L) null else Instant.ofEpochMilli(this).toString()
    }
}
