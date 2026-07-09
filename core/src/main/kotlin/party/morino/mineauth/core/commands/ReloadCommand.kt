package party.morino.mineauth.core.commands

import org.bukkit.command.CommandSender
import org.incendo.cloud.annotations.Command
import org.incendo.cloud.annotations.Permission
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.web.WebServer

@Command("mineauth|ma|mauth")
class ReloadCommand {
    @Command("reload")
    @Permission("moripaapi.command.reload")
    fun reload(sender: CommandSender) {
        sender.sendMessage("Reloading...")

        WebServer.stopServer()
        sender.sendMessage("Server stopped")

        FileUtils.loadFiles()
        sender.sendMessage("Files reloaded")

        FileUtils.settingDatabase()

        WebServer.settingServer()
        WebServer.startServer()
        sender.sendMessage("Server started")

        sender.sendMessage("Reloaded")
    }

}