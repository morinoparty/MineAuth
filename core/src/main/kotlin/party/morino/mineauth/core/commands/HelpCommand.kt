package party.morino.mineauth.core.commands

import org.bukkit.command.CommandSender
import revxrsal.commands.annotation.Command
import revxrsal.commands.annotation.Default
import revxrsal.commands.annotation.Description
import revxrsal.commands.annotation.Subcommand
import revxrsal.commands.help.CommandHelp

@Command("mineauth", "ma", "mauth")
class HelpCommand {
    @Subcommand("help")
    @Description("Shows the help menu")
    fun help(sender: CommandSender, helpEntries: CommandHelp<String>, @Default("1") page: Int) {
        for (entry in helpEntries.paginate(page, 7))  // 7 entries per page
            sender.sendRichMessage(entry)
    }
}