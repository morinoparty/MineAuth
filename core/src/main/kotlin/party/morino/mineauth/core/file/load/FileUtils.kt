package party.morino.mineauth.core.file.load

import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.database.DatabaseConnector
import party.morino.mineauth.core.file.load.config.ConfigLoader
import party.morino.mineauth.core.file.load.resources.AssetsResourceLoader
import party.morino.mineauth.core.file.load.resources.TemplatePageResourceLoader
import party.morino.mineauth.core.file.utils.KeyUtils

object FileUtils : KoinComponent {
    private val databaseConnector: DatabaseConnector by inject()

    fun loadFiles() {
        // 1. 統合設定ファイルを最初に読み込む
        //    これにより JWTConfigData, OAuthConfigData, WebServerConfigData が Koin に登録される
        ConfigLoader().load()

        // 2. 鍵の生成とJWKsの読み込み
        //    ConfigLoader で登録された JWTConfigData を使用する
        KeyUtils.init()

        // 3. リソースファイルの読み込み
        val loaders = listOf<FileLoaderInterface>(
            AssetsResourceLoader(),
            TemplatePageResourceLoader()
        )

        loaders.forEach {
            it.load()
        }
    }

    /**
     * データベースに接続する
     * 設定に基づいてSQLiteまたはMySQLに接続
     */
    fun settingDatabase() {
        databaseConnector.connect()
    }

    /**
     * データベース接続を切断する
     * プラグイン終了時に呼び出す
     */
    fun closeDatabase() {
        databaseConnector.disconnect()
    }
}
