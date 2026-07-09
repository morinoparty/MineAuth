package party.morino.mineauth.api.auth

import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import java.util.UUID

/**
 * 認証済みリクエスト元を表すsealed interface
 * `@Caller`アノテーション付きパラメータとしてハンドラーに注入される
 *
 * 実装はMineAuth本体が提供する（interfaceであるためテストではモック可能）
 */
sealed interface Principal {

    /** トークンに付与されたOAuthスコープの集合 */
    val scopes: Set<String>

    /** トークンを発行したOAuthクライアントのID（サービストークンの場合はnull） */
    val clientId: String?

    /**
     * ユーザートークンで認証されたプレイヤー
     */
    interface User : Principal {

        /** プレイヤーのMinecraft UUID */
        val uuid: UUID

        /** プレイヤーのOfflinePlayer表現（オフラインでも取得可能） */
        val offlinePlayer: OfflinePlayer

        /** オンラインの場合のPlayerインスタンス（オフライン時はnull — 404にはならない） */
        val onlinePlayer: Player?

        /**
         * プレイヤーが指定されたパーミッションを持つか確認する
         * プレイヤーがオフラインの場合はfalseを返す
         *
         * @param node パーミッションノード
         * @return パーミッションを持つ場合true
         */
        fun hasPermission(node: String): Boolean
    }

    /**
     * サービスアカウントトークンで認証されたサービス
     */
    interface Service : Principal {

        /** サービスアカウントのID */
        val accountId: String
    }
}
