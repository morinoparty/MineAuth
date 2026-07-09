package party.morino.mineauth.core

import com.github.shynixn.mccoroutine.bukkit.SuspendingJavaPlugin
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.plugin.ServicePriority
import org.incendo.cloud.annotations.AnnotationParser
import org.incendo.cloud.bukkit.CloudBukkitCapabilities
import org.incendo.cloud.execution.ExecutionCoordinator
import org.incendo.cloud.kotlin.coroutines.annotations.installCoroutineSupport
import org.incendo.cloud.paper.LegacyPaperCommandManager
import org.incendo.cloud.setting.ManagerSetting
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.getOrNull
import org.koin.dsl.module
import party.morino.mineauth.api.MineAuthApi
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.config.PluginDirectoryImpl
import party.morino.mineauth.core.plugin.MineAuthApiImpl
import party.morino.mineauth.core.plugin.PluginDisableListener
import party.morino.mineauth.core.plugin.pluginModule
import party.morino.mineauth.core.database.DatabaseConnector
import party.morino.mineauth.core.commands.OAuthClientCommand
import party.morino.mineauth.core.commands.RegisterCommand
import party.morino.mineauth.core.commands.ReloadCommand
import party.morino.mineauth.core.commands.ServiceAccountCommand
import party.morino.mineauth.core.commands.VersionCommand
import party.morino.mineauth.core.commands.parser.ClientIdParser
import party.morino.mineauth.core.commands.parser.ServiceNameParser
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.integration.IntegrationInitializer
import party.morino.mineauth.core.web.WebServer
import party.morino.mineauth.core.web.router.common.server.PluginInfoService
import party.morino.mineauth.core.web.router.common.server.PluginInfoServiceImpl

open class MineAuth: SuspendingJavaPlugin() {
    private lateinit var plugin: MineAuth
    override suspend fun onEnableAsync() {
        println("MineAuth is enabling...")
        plugin = this
        setCommand()
        setupKoin()
        registerApi()
        FileUtils.loadFiles()
        FileUtils.settingDatabase()
        IntegrationInitializer.initialize()
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            logger.info("MineAuth is enabled!")
            WebServer.settingServer()
            WebServer.startServer()
        })
    }

    /**
     * MineAuthApiをServicesManagerに登録する
     * ディスパッチャ方式によりWebサーバー起動前でもエンドポイント登録が可能なため、
     * サーバー起動を待たずに公開できる
     */
    private fun registerApi() {
        val apiImpl = org.koin.java.KoinJavaComponent.get<MineAuthApiImpl>(MineAuthApiImpl::class.java)
        server.servicesManager.register(MineAuthApi::class.java, apiImpl, this, ServicePriority.Normal)
        // プラグイン無効化時の自動登録解除（クラスローダーリーク防止）
        server.pluginManager.registerEvents(PluginDisableListener(apiImpl), this)
    }


    private fun setupKoin() {
        // テスト環境では既にKoinが初期化されている場合があるのでチェック
        if (getOrNull() != null) {
            return
        }

        val appModule = module {
            single<MineAuth> { this@MineAuth }
            single<PluginDirectory> { PluginDirectoryImpl() }
            single { DatabaseConnector() }
            single<PluginInfoService> { PluginInfoServiceImpl() }
        }

        getOrNull() ?: GlobalContext.startKoin {
            modules(appModule, pluginModule)
        }
    }

    override suspend fun onDisableAsync() {
        WebServer.stopServer()
        FileUtils.closeDatabase()
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

        // ClientIdParserをParserRegistryに登録
        commandManager.parserRegistry().registerParser(ClientIdParser.clientIdParser())
        // ServiceNameParserをParserRegistryに登録
        commandManager.parserRegistry().registerParser(ServiceNameParser.serviceNameParser())

        val annotationParser = AnnotationParser(commandManager, CommandSender::class.java)
        annotationParser.installCoroutineSupport()

        with(annotationParser) {
            parse(
                RegisterCommand(),
                ReloadCommand(),
                OAuthClientCommand(),
                ServiceAccountCommand(),
                VersionCommand(),
            )
        }
    }

}