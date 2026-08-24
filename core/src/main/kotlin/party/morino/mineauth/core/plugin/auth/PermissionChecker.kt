package party.morino.mineauth.core.plugin.auth

import java.util.UUID

/**
 * プレイヤーのパーミッションを評価するインターフェース
 *
 * オフラインプレイヤーの評価はパーミッションプラグインのストレージアクセスを伴うため、
 * suspend関数として定義しサーバースレッドをブロックしないようにする
 */
interface PermissionChecker {

    /**
     * プレイヤーが指定されたパーミッションを持つか評価する
     *
     * @param uuid プレイヤーのUUID
     * @param node パーミッションノード
     * @return 評価結果
     */
    suspend fun check(uuid: UUID, node: String): PermissionCheckResult
}
