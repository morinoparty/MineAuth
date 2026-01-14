package party.morino.mineauth.core.plugin

import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

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
         * プラグイン名を小文字に変換してパスに使用する
         *
         * @param plugin 登録元のJavaPluginインスタンス
         * @return 作成されたPluginContext
         */
        fun from(plugin: JavaPlugin): PluginContext {
            // プラグイン名を小文字に正規化
            // 例: QuickShopHikari -> quickshophikari, MineAuth -> mineauth
            val pluginName = plugin.name.lowercase(Locale.ROOT)
            return PluginContext(
                plugin = plugin,
                basePath = "/api/v1/plugins/$pluginName"
            )
        }
    }
}
