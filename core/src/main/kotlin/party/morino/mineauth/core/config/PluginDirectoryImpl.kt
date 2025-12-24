package party.morino.mineauth.core.config

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.MineAuth
import java.io.File

/**
 * プラグインのディレクトリを管理する実装クラス
 */
class PluginDirectoryImpl : PluginDirectory, KoinComponent {
    private val plugin: MineAuth by inject()
    private val rootDirectoryFile: File by lazy { plugin.dataFolder }
    private val clientsDirectoryFile: File by lazy { File(rootDirectoryFile, "clients") }
    private val templatesDirectoryFile: File by lazy { File(rootDirectoryFile, "templates") }
    private val assetsDirectoryFile: File by lazy { File(rootDirectoryFile, "assets") }
    private val loadDirectoryFile: File by lazy { File(rootDirectoryFile, "load") }

    override fun getRootDirectory(): File {
        if (!rootDirectoryFile.exists()) {
            rootDirectoryFile.mkdirs()
        }
        return rootDirectoryFile
    }

    override fun getClientsDirectory(): File {
        if (!clientsDirectoryFile.exists()) {
            clientsDirectoryFile.mkdirs()
        }
        return clientsDirectoryFile
    }

    override fun getTemplatesDirectory(): File {
        if (!templatesDirectoryFile.exists()) {
            templatesDirectoryFile.mkdirs()
        }
        return templatesDirectoryFile
    }

    override fun getAssetsDirectory(): File {
        if (!assetsDirectoryFile.exists()) {
            assetsDirectoryFile.mkdirs()
        }
        return assetsDirectoryFile
    }

    override fun getLoadDirectory(): File {
        if (!loadDirectoryFile.exists()) {
            loadDirectoryFile.mkdirs()
        }
        return loadDirectoryFile
    }
}
