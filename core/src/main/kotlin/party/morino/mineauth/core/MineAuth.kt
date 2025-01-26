package party.morino.mineauth.core

import com.github.shynixn.mccoroutine.bukkit.SuspendingJavaPlugin
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.java.JavaPlugin
import org.incendo.cloud.annotations.AnnotationParser
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.kotlin.coroutines.annotations.installCoroutineSupport
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.setting.ManagerSetting
import org.koin.core.context.GlobalContext
import org.koin.dsl.module
import party.morino.mineauth.api.MineAuthAPI
import party.morino.mineauth.api.RegisterHandler
import party.morino.mineauth.core.commands.RegisterCommand
import party.morino.mineauth.core.commands.ReloadCommand
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.integration.IntegrationInitializer
import party.morino.mineauth.core.web.WebServer

open class MineAuth: SuspendingJavaPlugin() , MineAuthAPI {
    private lateinit var plugin: MineAuth
    override suspend fun onEnableAsync() {
        plugin = this
        setCommand()
        setupKoin()
        FileUtils.loadFiles()
        FileUtils.settingDatabase()
        IntegrationInitializer.initialize()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            WebServer.settingServer()
            WebServer.startServer()
        })
    }


    private fun setupKoin() {
        val appModule = module {
            single<MineAuth> { this@MineAuth }
        }

        GlobalContext.getOrNull() ?: GlobalContext.startKoin {
            modules(appModule)
        }
    }

    override suspend fun onDisableAsync() {
        WebServer.stopServer()
    }

    private fun setCommand() {
        val commandManager = LegacyPaperCommandManager.createNative(
            this,
            ExecutionCoordinator.simpleCoordinator()
        )

        if (commandManager.hasCapability(CloudBukkitCapabilities.ASYNCHRONOUS_COMPLETION)) {
            commandManager.registerAsynchronousCompletions()
        }

        commandManager.settings().set(ManagerSetting.ALLOW_UNSAFE_REGISTRATION, true)

        val annotationParser = AnnotationParser(commandManager, CommandSender::class.java)
        annotationParser.installCoroutineSupport()

        with(annotationParser) {
            parse(
                RegisterCommand(),
                ReloadCommand(),
            )
        }
    }

    override fun createHandler(plugin: JavaPlugin): RegisterHandler {
        TODO("Not yet implemented")
    }

}