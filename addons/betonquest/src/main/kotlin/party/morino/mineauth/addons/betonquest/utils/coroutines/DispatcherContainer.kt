package party.morino.mineauth.addons.betonquest.utils.coroutines

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.addons.betonquest.BetonQuestAddon
import kotlin.coroutines.CoroutineContext

/**
 * コルーチンディスパッチャーのコンテナ
 * 遅延初期化でディスパッチャーを管理する
 */
object DispatcherContainer {

    private var syncCoroutine: CoroutineContext? = null

    /**
     * メインスレッド用のコルーチンコンテキストを取得する
     */
    val sync: CoroutineContext
        get() {
            if (syncCoroutine == null) {
                syncCoroutine = MinecraftCoroutineDispatcher(JavaPlugin.getPlugin(BetonQuestAddon::class.java))
            }
            return syncCoroutine!!
        }
}
