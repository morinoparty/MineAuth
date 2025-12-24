package party.morino.mineauth.core.mocks.config

import party.morino.mineauth.api.config.PluginDirectory
import java.io.File

/**
 * プラグインのディレクトリを管理するモッククラス
 * テスト用リソースディレクトリを指す
 */
class PluginDirectoryMock : PluginDirectory {
    private val rootDirectory: File = File("src/test/resources/plugins/mineauth")
    private val clientsDirectory: File = File(rootDirectory, "clients")
    private val templatesDirectory: File = File(rootDirectory, "templates")
    private val assetsDirectory: File = File(rootDirectory, "assets")
    private val loadDirectory: File = File(rootDirectory, "load")

    override fun getRootDirectory(): File {
        if (!rootDirectory.exists()) {
            rootDirectory.mkdirs()
        }
        return rootDirectory
    }

    override fun getClientsDirectory(): File {
        if (!clientsDirectory.exists()) {
            clientsDirectory.mkdirs()
        }
        return clientsDirectory
    }

    override fun getTemplatesDirectory(): File {
        if (!templatesDirectory.exists()) {
            templatesDirectory.mkdirs()
        }
        return templatesDirectory
    }

    override fun getAssetsDirectory(): File {
        if (!assetsDirectory.exists()) {
            assetsDirectory.mkdirs()
        }
        return assetsDirectory
    }

    override fun getLoadDirectory(): File {
        if (!loadDirectory.exists()) {
            loadDirectory.mkdirs()
        }
        return loadDirectory
    }
}
