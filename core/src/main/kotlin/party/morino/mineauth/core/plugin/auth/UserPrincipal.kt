package party.morino.mineauth.core.plugin.auth

import net.luckperms.api.util.Tristate
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import java.util.UUID

/**
 * ユーザートークンで認証されたプレイヤーのPrincipal実装
 *
 * @property uuid プレイヤーのMinecraft UUID
 * @property scopes トークンに付与されたOAuthスコープ
 * @property clientId トークンを発行したOAuthクライアントID
 */
class UserPrincipal(
    override val uuid: UUID,
    override val scopes: Set<String>,
    override val clientId: String?
) : Principal.User {

    // Bukkit APIは呼び出し時点の状態を返すため、プロパティアクセスごとに取得する
    override val offlinePlayer: OfflinePlayer
        get() = Bukkit.getOfflinePlayer(uuid)

    override val onlinePlayer: Player?
        get() = Bukkit.getPlayer(uuid)

    /**
     * プレイヤーのパーミッションを確認する
     *
     * オンライン時はPaper標準のAPIで評価する
     * オフライン時はLuckPermsのキャッシュを参照する（同期APIのためストレージI/Oは行わない）
     * どちらでも評価できない場合は安全側に倒してfalseを返す
     */
    override fun hasPermission(node: String): Boolean {
        // オンラインならサーバーの権限プラグインの結果がそのまま反映される
        onlinePlayer?.let { return it.hasPermission(node) }

        // オフラインはLuckPermsのキャッシュのみを参照する（ブロッキングI/Oを避けるため）
        return when (LuckPermsIntegration.checkCachedPermission(uuid, node)) {
            Tristate.TRUE -> true
            Tristate.FALSE -> false
            // 未設定ノードはオンライン時と同様にBukkitのデフォルト値で解決する
            Tristate.UNDEFINED -> PermissionDefaults.resolve(uuid, node)
            null -> false
        }
    }
}
