package party.morino.mineauth.addons.betonquest.utils.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import kotlin.coroutines.CoroutineContext

/**
 * メインスレッドでコルーチンを実行するディスパッチャー
 * MCCoroutineを使わず、Bukkitのスケジューラを直接使用することで
 * クラスローダー間の互換性問題を回避する
 *
 * @param plugin プラグインインスタンス（スケジューラ登録用）
 */
class MinecraftCoroutineDispatcher(private val plugin: Plugin) : CoroutineDispatcher() {
    /**
     * コルーチンを適切なスレッドでディスパッチする
     * メインスレッドにいる場合は直接実行、そうでなければスケジューラで実行
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (Bukkit.isPrimaryThread()) {
            block.run()
        } else {
            plugin.server.scheduler.runTask(plugin, block)
        }
    }
}
