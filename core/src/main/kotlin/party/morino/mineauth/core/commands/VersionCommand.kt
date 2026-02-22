package party.morino.mineauth.core.commands

import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.incendo.cloud.annotations.Command
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.plugin.PluginRouteRegistry

/**
 * バージョン情報を表示するコマンド
 * MineAuth本体のバージョンと登録済みアドオンの一覧を表示する
 */
@Command("mineauth|ma|mauth")
class VersionCommand : KoinComponent {

    private val plugin: MineAuth by inject()
    private val routeRegistry: PluginRouteRegistry by inject()

    /**
     * バージョン情報を表示する
     * 本体のバージョンと、登録済みアドオンの名前・バージョンを一覧で表示する
     */
    @Command("version")
    fun version(sender: CommandSender) {
        // MineAuth本体のバージョンを取得
        val coreVersion = plugin.pluginMeta.version

        sender.sendRichMessage("<gold><bold>MineAuth</bold></gold> <gray>${coreVersion}</gray>")

        // 登録済みアドオンの一覧を取得
        val registeredPlugins = routeRegistry.getRegisteredPlugins()

        if (registeredPlugins.isEmpty()) {
            sender.sendRichMessage("<gray>  No addons registered.</gray>")
            return
        }

        sender.sendRichMessage("<yellow>Addons:</yellow>")
        for (pluginName in registeredPlugins) {
            // Bukkit PluginManagerからプラグインのバージョンを取得
            val addonPlugin = Bukkit.getPluginManager().getPlugin(pluginName)
            val addonVersion = addonPlugin?.pluginMeta?.version ?: "unknown"
            sender.sendRichMessage("<gray>  - <white>${pluginName}</white> ${addonVersion}</gray>")
        }
    }
}
