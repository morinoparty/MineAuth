package party.morino.mineauth.addons.quickshop.utils.coroutines

import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.core.MineAuth
import kotlin.coroutines.CoroutineContext

object DispatcherContainer {

    private var asyncCoroutine: CoroutineContext? = null
    private var syncCoroutine: CoroutineContext? = null

    /**
     * Gets the async coroutine context.
     */
    val async: CoroutineContext
        get() {
            if (asyncCoroutine == null) {
                asyncCoroutine = AsyncCoroutineDispatcher(JavaPlugin.getPlugin(MineAuth::class.java))
            }
            return asyncCoroutine!!
        }

    /**
     * Gets the sync coroutine context.
     */
    val sync: CoroutineContext
        get() {
            if (syncCoroutine == null) {
                syncCoroutine = MinecraftCoroutineDispatcher(JavaPlugin.getPlugin(MineAuth::class.java))
            }

            return syncCoroutine!!
        }
}