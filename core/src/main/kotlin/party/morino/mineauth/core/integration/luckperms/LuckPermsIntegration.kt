package party.morino.mineauth.core.integration.luckperms

import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.query.QueryOptions
import org.bukkit.Bukkit.getServer
import party.morino.mineauth.core.integration.Integration
import java.util.UUID

/**
 * LuckPerms統合クラス
 * プレイヤーのグループ情報をOIDC rolesクレームとして提供する
 */
object LuckPermsIntegration : Integration() {
    override var available: Boolean = false
    override val name: String = "LuckPerms"

    // LuckPerms APIインスタンス（初期化後にのみ使用可能）
    private lateinit var luckPerms: LuckPerms

    override fun initialize() {
        // LuckPermsプラグインの存在確認
        val plugin = getServer().pluginManager.getPlugin(name)
        if (plugin == null) {
            mineAuth.logger.info("LuckPerms not found, roles scope will be disabled")
            return
        }

        // LuckPerms APIの取得を試行
        try {
            luckPerms = LuckPermsProvider.get()
            available = true
            mineAuth.logger.info("LuckPerms found, roles scope enabled")
        } catch (e: IllegalStateException) {
            // LuckPermsがまだ初期化されていない場合
            mineAuth.logger.warning("LuckPerms API not available: ${e.message}")
            available = false
        }
    }

    /**
     * プレイヤーのグループ名一覧を取得する
     *
     * @param playerUuid プレイヤーのUUID
     * @return グループ名のリスト（LuckPerms未使用時は空リスト）
     */
    fun getPlayerGroups(playerUuid: UUID): List<String> {
        if (!available) return emptyList()

        // ユーザー情報を取得（キャッシュから）
        val user = luckPerms.userManager.getUser(playerUuid) ?: return emptyList()

        // デフォルトのQueryOptionsでユーザーが所属するすべてのグループを取得
        val inheritedGroups = user.getInheritedGroups(QueryOptions.nonContextual())
        return inheritedGroups.map { it.name }
    }
}
