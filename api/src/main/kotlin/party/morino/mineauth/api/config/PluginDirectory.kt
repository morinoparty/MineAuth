package party.morino.mineauth.api.config

import java.io.File

/**
 * プラグインのディレクトリを管理するインターフェース
 */
interface PluginDirectory {
    /**
     * プラグインのルートディレクトリを取得する
     * @return プラグインのルートディレクトリ
     */
    fun getRootDirectory(): File

    /**
     * クライアント設定が格納されているディレクトリを取得する
     * @return クライアント設定ディレクトリ
     */
    fun getClientsDirectory(): File

    /**
     * Velocityテンプレートが格納されていたディレクトリを取得する
     * 認可画面はReactで描画しJARに同梱するようになったため、MineAuth自身はこのディレクトリを使用しない
     * @return テンプレートディレクトリ
     */
    @Deprecated("Templates are no longer loaded from the plugin directory. The authorization page is bundled in the JAR.")
    fun getTemplatesDirectory(): File

    /**
     * 静的アセットが格納されているディレクトリを取得する
     * @return アセットディレクトリ
     */
    fun getAssetsDirectory(): File

    /**
     * 設定ファイルが格納されているディレクトリを取得する
     * @return 設定ファイルディレクトリ (web-server.json, oauth.json, jwt.jsonなど)
     */
    fun getLoadDirectory(): File
}
