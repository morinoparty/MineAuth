package party.morino.mineauth.core.plugin

import org.bukkit.plugin.java.JavaPlugin

/**
 * 外部プラグインのコンテキスト情報を保持するクラス
 * プラグイン名から基本パスを生成し、ルート登録時に使用する
 *
 * @property plugin 登録元のJavaPluginインスタンス
 * @property basePath プラグイン用の基本パス（例: /api/v1/plugins/quickshop-hikari）
 */
data class PluginContext(
    val plugin: JavaPlugin,
    val basePath: String
) {
    companion object {
        /**
         * プラグインからコンテキストを作成する
         * プラグイン名をケバブケースに変換してパスに使用する
         *
         * @param plugin 登録元のJavaPluginインスタンス
         * @return 作成されたPluginContext
         */
        fun from(plugin: JavaPlugin): PluginContext {
            // プラグイン名をケバブケースに正規化
            // 例: QuickShopHikari -> quickshop-hikari
            val pluginName = plugin.name
                .replace(Regex("([a-z])([A-Z])"), "$1-$2")
                .lowercase()
            return PluginContext(
                plugin = plugin,
                basePath = "/api/v1/plugins/$pluginName"
            )
        }
    }
}
