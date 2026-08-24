package party.morino.mineauth.core.plugin.auth

import net.luckperms.api.util.Tristate
import org.bukkit.Bukkit
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import java.util.UUID

/**
 * PermissionCheckerの既定実装
 *
 * 評価は次の優先順で行う:
 * 1. オンラインの場合はPaper標準の`Player#hasPermission`（サーバーの権限プラグインの結果がそのまま反映される）
 * 2. オフラインの場合はLuckPerms APIでストレージからユーザーをロードして評価する
 * 3. どちらも使えない場合は評価不能として返す（黙って許可しない）
 *
 * BukkitにはオフラインプレイヤーのPermissible相当が存在しないため、
 * オフライン評価はLuckPermsの導入が前提となる
 */
class DefaultPermissionChecker : PermissionChecker {

    override suspend fun check(uuid: UUID, node: String): PermissionCheckResult {
        // オンラインならPaper標準のAPIで評価する（コンテキスト付きの正確な結果が得られる）
        Bukkit.getPlayer(uuid)?.let { player ->
            return if (player.hasPermission(node)) PermissionCheckResult.GRANTED else PermissionCheckResult.DENIED
        }

        // オフラインはLuckPermsにフォールバックする（未導入時はnull）
        return when (LuckPermsIntegration.checkPermission(uuid, node)) {
            Tristate.TRUE -> PermissionCheckResult.GRANTED
            Tristate.FALSE -> PermissionCheckResult.DENIED
            // 未設定ノードはオンライン時と同様にBukkitのデフォルト値で解決する
            Tristate.UNDEFINED -> resolveDefault(uuid, node)
            null -> PermissionCheckResult.UNRESOLVABLE
        }
    }

    /**
     * 権限プラグインで未設定だったノードを、Bukkitに登録されたデフォルト値で解決する
     *
     * @param uuid プレイヤーのUUID
     * @param node パーミッションノード
     * @return デフォルト値による評価結果
     */
    private fun resolveDefault(uuid: UUID, node: String): PermissionCheckResult =
        if (PermissionDefaults.resolve(uuid, node)) PermissionCheckResult.GRANTED else PermissionCheckResult.DENIED
}
