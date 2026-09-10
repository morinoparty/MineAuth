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
     * オフライン時はLuckPermsで評価する（キャッシュになければストレージからのロードを同期的に待つため、
     * Minecraftのメインスレッドからは呼ばないこと）
     */
    override fun hasPermission(node: String): Boolean {
        // オンラインならサーバーの権限プラグインの結果がそのまま反映される
        onlinePlayer?.let { return it.hasPermission(node) }

        // オフラインはLuckPermsで評価する（`@Authenticated(permission)`と同じ規則）
        return when (LuckPermsIntegration.checkPermissionBlocking(uuid, node)) {
            Tristate.TRUE -> true
            Tristate.FALSE -> false
            // 未設定ノードはオンライン時と同様にBukkitのデフォルト値で解決する
            Tristate.UNDEFINED -> PermissionDefaults.resolve(uuid, node)
        }
    }
}
