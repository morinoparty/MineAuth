package party.morino.mineauth.core.integration.luckperms

import kotlinx.coroutines.future.await
import net.luckperms.api.LuckPerms
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.model.user.User
import net.luckperms.api.query.QueryOptions
import net.luckperms.api.util.Tristate
import party.morino.mineauth.core.integration.Integration
import java.util.UUID

/**
 * LuckPerms統合クラス
 *
 * LuckPermsはMineAuthのハード依存（plugin.ymlの`depend`）であり、常に利用可能な前提で動作する。
 * オフラインプレイヤーの権限評価とOIDC rolesクレームの提供に使用する。
 *
 * APIインスタンスは初期化順に依存しないよう、利用のたびに[LuckPermsProvider]から取得する
 * （静的フィールドの読み出しなので追加コストはない）。
 */
object LuckPermsIntegration : Integration() {
    // ハード依存のため常に利用可能
    override var available: Boolean = true
    override val name: String = "LuckPerms"

    // LuckPerms APIインスタンス（LuckPermsが有効化済みでなければIllegalStateException）
    private val luckPerms: LuckPerms
        get() = LuckPermsProvider.get()

    override fun initialize() {
        // ハード依存なので、ここで取得できない場合は設定ミス（依存宣言の欠落・読み込み順の異常）として
        // 黙って無効化せず、プラグインの有効化を失敗させる
        try {
            LuckPermsProvider.get()
        } catch (e: IllegalStateException) {
            throw IllegalStateException(
                "LuckPerms API is not available. MineAuth requires LuckPerms to be installed and enabled.", e
            )
        }
        mineAuth.logger.info("LuckPerms found, offline permission evaluation and roles scope enabled")
    }

    /**
     * プレイヤーのグループ名一覧を取得する
     * オフラインプレイヤーにも対応するため、キャッシュになければストレージから非同期でロードする
     *
     * @param playerUuid プレイヤーのUUID
     * @return グループ名のリスト
     */
    suspend fun getPlayerGroups(playerUuid: UUID): List<String> {
        // まずキャッシュからユーザー情報を取得試行（オンラインプレイヤーの場合は高速）
        val cachedUser = luckPerms.userManager.getUser(playerUuid)
        if (cachedUser != null) {
            // キャッシュにある場合はそのまま使用
            val inheritedGroups = cachedUser.getInheritedGroups(QueryOptions.nonContextual())
            return inheritedGroups.map { it.name }
        }

        // キャッシュにない場合は非同期でストレージからロード
        val loadedUser = luckPerms.userManager.loadUser(playerUuid).await()

        return try {
            // デフォルトのQueryOptionsでユーザーが所属するすべてのグループを取得
            val inheritedGroups = loadedUser.getInheritedGroups(QueryOptions.nonContextual())
            inheritedGroups.map { it.name }
        } finally {
            // 明示的にロードしたユーザーはクリーンアップしてメモリを解放
            luckPerms.userManager.cleanupUser(loadedUser)
        }
    }

    /**
     * プレイヤーのパーミッションをLuckPermsで評価する
     * オフラインプレイヤーでも評価できるよう、キャッシュになければストレージから非同期でロードする
     *
     * @param playerUuid プレイヤーのUUID
     * @param node パーミッションノード
     * @return 評価結果のTristate
     */
    suspend fun checkPermission(playerUuid: UUID, node: String): Tristate {
        // キャッシュにあればそのまま評価する（オンラインプレイヤーは常にキャッシュ済み）
        val cachedUser = luckPerms.userManager.getUser(playerUuid)
        if (cachedUser != null) {
            return evaluate(cachedUser, node)
        }

        // キャッシュにない場合は非同期でストレージからロードする
        val loadedUser = luckPerms.userManager.loadUser(playerUuid).await()

        return try {
            evaluate(loadedUser, node)
        } finally {
            // 明示的にロードしたユーザーはクリーンアップしてメモリを解放
            luckPerms.userManager.cleanupUser(loadedUser)
        }
    }

    /**
     * プレイヤーのパーミッションを同期的に評価する（suspend関数を使えない同期APIから呼び出す用）
     *
     * キャッシュにあればI/Oなしで評価し、なければストレージからのロード完了を待つ。
     * 待機中は呼び出しスレッドをブロックするため、Minecraftのメインスレッドからは呼ばないこと。
     *
     * @param playerUuid プレイヤーのUUID
     * @param node パーミッションノード
     * @return 評価結果のTristate
     */
    fun checkPermissionBlocking(playerUuid: UUID, node: String): Tristate {
        val cachedUser = luckPerms.userManager.getUser(playerUuid)
        if (cachedUser != null) {
            return evaluate(cachedUser, node)
        }

        // ストレージからのロードを同期的に待つ
        val loadedUser = luckPerms.userManager.loadUser(playerUuid).join()
        return try {
            evaluate(loadedUser, node)
        } finally {
            luckPerms.userManager.cleanupUser(loadedUser)
        }
    }

    /**
     * ロード済みユーザーに対してパーミッションを評価する
     * オンラインならプレイヤーのコンテキスト、オフラインなら静的コンテキストを使用する
     *
     * @param user LuckPermsのユーザー
     * @param node パーミッションノード
     * @return 評価結果のTristate
     */
    private fun evaluate(user: User, node: String): Tristate {
        val queryOptions = luckPerms.contextManager.getQueryOptions(user)
            .orElseGet { luckPerms.contextManager.staticQueryOptions }
        return user.cachedData.getPermissionData(queryOptions).checkPermission(node)
    }
}
