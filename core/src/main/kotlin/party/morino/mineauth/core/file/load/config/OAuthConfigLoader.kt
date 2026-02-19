package party.morino.mineauth.core.file.load.config

import kotlinx.serialization.encodeToString
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.file.data.OAuthConfigData
import party.morino.mineauth.core.utils.json
import java.io.File

class OAuthConfigLoader: AbstractConfigLoader() {
    override val configFile: File = plugin.dataFolder.resolve("load").resolve("oauth.json")

    override fun load() {
        if (!configFile.exists()) {
            plugin.logger.info("${configFile.name} not found. Creating new one.")
            configFile.parentFile.mkdirs()
            configFile.createNewFile()
            val configData = OAuthConfigData("MineAuth", "/assets/lock.svg")
            configFile.writeText(json.encodeToString(configData))
        }
        val configData: OAuthConfigData = json.decodeFromString(configFile.readText())

        loadKoinModules(module {
            single { configData }
        })
    }
}