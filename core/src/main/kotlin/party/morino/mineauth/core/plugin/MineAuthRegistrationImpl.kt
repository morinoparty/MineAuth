package party.morino.mineauth.core.plugin

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.api.MineAuthRegistration
import party.morino.mineauth.api.RegisteredEndpoint
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MineAuthRegistrationインターフェースの実装
 *
 * @property plugin 登録元プラグイン
 * @property namespace 登録された名前空間
 * @property basePath マウントされたベースパス
 * @property endpoints マウントされたエンドポイントの一覧
 * @property onUnregister 登録解除時のコールバック
 */
class MineAuthRegistrationImpl(
    override val plugin: JavaPlugin,
    val namespace: String,
    override val basePath: String,
    override val endpoints: List<RegisteredEndpoint>,
    private val onUnregister: (MineAuthRegistrationImpl) -> Unit
) : MineAuthRegistration {

    // 冪等性の保証: 複数回呼ばれても解除処理は1回のみ実行する
    private val unregistered = AtomicBoolean(false)

    override fun unregister() {
        if (unregistered.compareAndSet(false, true)) {
            onUnregister(this)
            plugin.logger.info("MineAuth: unregistered endpoints under $basePath")
        }
    }
}
