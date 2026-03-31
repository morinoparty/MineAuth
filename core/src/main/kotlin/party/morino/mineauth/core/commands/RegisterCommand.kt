package party.morino.mineauth.core.commands

import com.password4j.Password
import kotlinx.coroutines.Dispatchers
import org.apache.commons.lang3.RandomStringUtils
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.incendo.cloud.annotations.Command
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import party.morino.mineauth.core.database.UserAuthData

@Command("mineauth|ma|mauth")
class RegisterCommand {

    @Command("register")
    suspend fun register(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("You must be a player to use this command")
            return
        }
        sender.sendMessage("Registering...")
        val exist = newSuspendedTransaction(Dispatchers.IO) {
            UserAuthData.selectAll().where { UserAuthData.uuid eq sender.uniqueId.toString() }.count()
        } > 0
        if (exist) {
            sender.sendRichMessage("You are already registered if you want to change your password use <click:suggest_command:'/ma change'><yellow>/ma change</yellow></click>")
            return
        }
        val password = RandomStringUtils.randomAlphanumeric(20)
        sender.sendRichMessage(
            "Password is  $password  <yellow><click:copy_to_clipboard:'$password'>Click to copy</click></yellow>"
        )
        val hashed = Password.hash(password).addRandomSalt().addPepper().withArgon2().result

        newSuspendedTransaction(Dispatchers.IO) {
            UserAuthData.insert {
                it[uuid] = sender.uniqueId.toString()
                it[UserAuthData.password] = hashed
            }
        }
    }

    @Command("change")
    suspend fun change(sender: CommandSender) {
        if (sender !is Player) {
            sender.sendMessage("You must be a player to use this command")
            return
        }
        sender.sendMessage("Changing...")
        val exist = newSuspendedTransaction(Dispatchers.IO) {
            UserAuthData.selectAll().where { UserAuthData.uuid eq sender.uniqueId.toString() }.count()
        } > 0
        if (!exist) {
            sender.sendRichMessage("You are not already registered if you want to register use <click:suggest_command:'/ma register'><yellow>/ma register</yellow></click>")
            return
        }
        val password = RandomStringUtils.randomAlphanumeric(20)
        sender.sendRichMessage(
            "Password is  $password. <yellow><click:copy_to_clipboard:'$password'>Click to copy</click></yellow>"
        )
        val hashed = Password.hash(password).addRandomSalt().addPepper().withArgon2().result
        newSuspendedTransaction(Dispatchers.IO) {
            UserAuthData.update({ UserAuthData.uuid eq sender.uniqueId.toString() }) {
                it[UserAuthData.password] = hashed
            }
        }
    }
}