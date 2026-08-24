package party.morino.mineauth.core.plugin.auth

import org.bukkit.Bukkit
import org.bukkit.permissions.Permission
import java.util.UUID

/**
 * 権限プラグインで未設定だったパーミッションノードを、
 * Bukkitに登録されたデフォルト値で解決するユーティリティ
 *
 * オンライン時の`Player#hasPermission`はplugin.ymlのdefault設定を尊重するため、
 * オフライン評価でも同じ挙動に合わせるために使用する
 */
internal object PermissionDefaults {

    /**
     * パーミッションノードのデフォルト値を解決する
     *
     * @param uuid プレイヤーのUUID
     * @param node パーミッションノード
     * @return デフォルト値による評価結果
     */
    fun resolve(uuid: UUID, node: String): Boolean {
        // 未登録ノードのBukkitにおける既定はOP限定（Permission.DEFAULT_PERMISSION）
        val default = Bukkit.getPluginManager().getPermission(node)?.default ?: Permission.DEFAULT_PERMISSION
        return default.getValue(Bukkit.getOfflinePlayer(uuid).isOp)
    }
}
