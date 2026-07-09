package party.morino.mineauth.core.plugin

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent

/**
 * プラグイン無効化時に、そのプラグインが登録したエンドポイントを自動解除するリスナー
 * 無効化されたプラグインのハンドラーインスタンスへの参照を保持し続けると
 * クラスローダーリークになるため、必ず解除する
 *
 * @property api エンドポイント登録を管理するAPI実装
 */
class PluginDisableListener(
    private val api: MineAuthApiImpl
) : Listener {

    @EventHandler
    fun onPluginDisable(event: PluginDisableEvent) {
        api.unregisterAll(event.plugin.name)
    }
}
