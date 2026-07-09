package party.morino.mineauth.core.plugin.auth

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import party.morino.mineauth.api.auth.Principal
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
     * オフラインプレイヤーはBukkitでパーミッション評価ができないためfalseを返す
     */
    override fun hasPermission(node: String): Boolean =
        onlinePlayer?.hasPermission(node) ?: false
}
