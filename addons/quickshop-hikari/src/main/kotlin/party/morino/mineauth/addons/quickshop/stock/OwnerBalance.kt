package party.morino.mineauth.addons.quickshop.stock

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit

/**
 * Vault からショップのオーナーの所持金を取得する
 *
 * Vault または経済プラグインが無い場合は何も取得しない（買取ショップは空き容量だけで数える）。
 */
object OwnerBalance {
    /**
     * オーナーごとの所持金を取得する
     *
     * @param owners 所持金を取得するオーナー
     * @return オーナーごとの所持金。取得できなかったオーナーは含めない
     */
    suspend fun fetch(owners: Set<UUID>): Map<UUID, Double> {
        if (owners.isEmpty()) return emptyMap()
        val economy = economyOrNull() ?: return emptyMap()
        // オフラインのプレイヤーの所持金はディスクから読む経済プラグインもあるので、メインスレッドの外で取る
        return withContext(Dispatchers.IO) {
            owners.mapNotNull { uuid ->
                // 1 人分の取得に失敗しても、他のオーナーの所持金は使う
                runCatching { uuid to economy.getBalance(Bukkit.getOfflinePlayer(uuid)) }.getOrNull()
            }.toMap()
        }
    }

    /**
     * Vault の経済サービスを取得する
     *
     * @return 経済サービス。Vault が無い・経済プラグインが登録されていない場合はnull
     */
    private fun economyOrNull(): Economy? {
        // Vault の無いサーバーでは Economy クラスを読み込めないので、先にプラグインの有無を確かめる
        if (!Bukkit.getPluginManager().isPluginEnabled("Vault")) return null
        return runCatching { Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider }.getOrNull()
    }
}
