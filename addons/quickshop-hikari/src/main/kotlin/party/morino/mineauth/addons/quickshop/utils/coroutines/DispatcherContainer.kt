package party.morino.mineauth.addons.quickshop.utils.coroutines

import kotlin.coroutines.CoroutineContext
import org.bukkit.plugin.java.JavaPlugin
import party.morino.mineauth.addons.quickshop.QuickShopHikariAddon

object DispatcherContainer {

    private var asyncCoroutine: CoroutineContext? = null
    private var syncCoroutine: CoroutineContext? = null

    /**
     * Gets the async coroutine context.
     */
    val async: CoroutineContext
        get() {
            if (asyncCoroutine == null) {
                asyncCoroutine = AsyncCoroutineDispatcher(JavaPlugin.getPlugin(QuickShopHikariAddon::class.java))
            }
            return asyncCoroutine!!
        }

    /**
     * Gets the sync coroutine context.
     */
    val sync: CoroutineContext
        get() {
            if (syncCoroutine == null) {
                syncCoroutine = MinecraftCoroutineDispatcher(JavaPlugin.getPlugin(QuickShopHikariAddon::class.java))
            }

            return syncCoroutine!!
        }
}