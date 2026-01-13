package party.morino.mineauth.core.plugin

import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import java.util.concurrent.ConcurrentHashMap

/**
 * 登録されたプラグインルートを管理するレジストリ
 * スレッドセーフなConcurrentHashMapを使用してルートを管理する
 */
class PluginRouteRegistry : KoinComponent {
    // プラグイン名 -> ルート設定関数のマップ
    // ConcurrentHashMapを使用してスレッドセーフを保証
    private val registeredRoutes = ConcurrentHashMap<String, Route.() -> Unit>()

    /**
     * プラグインのルートを登録する
     *
     * @param pluginName プラグイン名
     * @param routeConfig ルート設定関数
     */
    fun register(pluginName: String, routeConfig: Route.() -> Unit) {
        registeredRoutes[pluginName] = routeConfig
    }

    /**
     * プラグインのルートを削除する（プラグイン無効化時）
     *
     * @param pluginName 削除するプラグイン名
     */
    fun unregister(pluginName: String) {
        registeredRoutes.remove(pluginName)
    }

    /**
     * 全登録ルートをKtorルーティングに適用する
     * WebServer.module()から呼び出される
     *
     * @param route Ktorのルートコンテキスト
     */
    fun applyAll(route: Route) {
        for ((_, routeConfig) in registeredRoutes) {
            route.routeConfig()
        }
    }

    /**
     * 登録済みプラグインのリストを取得する
     *
     * @return 登録済みプラグイン名のリスト
     */
    fun getRegisteredPlugins(): List<String> = registeredRoutes.keys.toList()
}
