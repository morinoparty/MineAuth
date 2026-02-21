package party.morino.mineauth.core.file.load.config

import kotlinx.serialization.encodeToString
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.OAuthConfigData
import party.morino.mineauth.core.file.data.WebServerConfigData
import java.io.File

/**
 * 統合設定ファイルローダー
 * Issue #66: 複数の設定ファイルを1つにまとめる
 *
 * plugins/MineAuth/config.json を読み込み、
 * 後方互換性のために各データクラスにも変換してKoinに登録する
 */
class ConfigLoader : AbstractConfigLoader() {
    override val configFile: File = plugin.dataFolder.resolve("config.json")

    override fun load() {
        // 設定ファイルが存在しない場合はデフォルト値で作成
        if (!configFile.exists()) {
            plugin.logger.info("config.json not found. Creating new one.")
            configFile.parentFile.mkdirs()
            configFile.createNewFile()
            val defaultConfig = MineAuthConfig()
            configFile.writeText(json.encodeToString(defaultConfig))
        }

        val config: MineAuthConfig = json.decodeFromString(configFile.readText())

        // 統合設定をKoinに登録
        loadKoinModules(module {
            single { config }
        })

        // 後方互換性: 既存のデータクラスにも変換して登録
        loadLegacyModules(config)
    }

    /**
     * 後方互換性のために既存のデータクラスに変換してKoinに登録
     * 既存コードが JWTConfigData, OAuthConfigData, WebServerConfigData を
     * 直接injectしている場合でも動作するようにする
     */
    private fun loadLegacyModules(config: MineAuthConfig) {
        // JWTConfigData への変換
        val jwtConfigData = JWTConfigData(
            issuer = config.jwt.issuer,
            realm = config.jwt.realm,
            privateKeyFile = config.jwt.privateKeyFile,
            keyId = config.jwt.keyId
        )

        // OAuthConfigData への変換
        val oauthConfigData = OAuthConfigData(
            applicationName = config.oauth.applicationName,
            logoUrl = config.oauth.logoUrl
        )

        // WebServerConfigData への変換
        val webServerConfigData = WebServerConfigData(
            port = config.server.port,
            ssl = config.server.ssl
        )

        loadKoinModules(module {
            single { jwtConfigData }
            single { oauthConfigData }
            single { webServerConfigData }
        })
    }
}
