package party.morino.mineauth.api

import org.bukkit.plugin.java.JavaPlugin

/**
 * エンドポイント登録の結果を表すハンドル
 *
 * 実際にマウントされたパスとエンドポイント一覧を公開し、
 * 明示的な登録解除を可能にする。
 * プラグイン無効化時にはMineAuth側で自動的に登録解除されるため、
 * 通常は[unregister]を呼ぶ必要はない。
 */
interface MineAuthRegistration : AutoCloseable {

    /** 登録元プラグイン */
    val plugin: JavaPlugin

    /** 実際にマウントされたベースパス（例: /api/v1/plugins/vault） */
    val basePath: String

    /** 実際にマウントされたエンドポイントの一覧 */
    val endpoints: List<RegisteredEndpoint>

    /**
     * 登録を解除する（冪等）
     * 呼び出し後、このRegistrationのエンドポイントは即座に404を返すようになる
     */
    fun unregister()

    /** [unregister]のエイリアス（use/try-with-resources用） */
    override fun close() = unregister()
}
