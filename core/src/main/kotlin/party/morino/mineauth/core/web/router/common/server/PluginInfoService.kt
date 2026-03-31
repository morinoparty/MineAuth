package party.morino.mineauth.core.web.router.common.server

import party.morino.mineauth.api.model.common.PluginInfoData

/**
 * インストール済みプラグインの情報を取得するサービス
 */
interface PluginInfoService {
    /**
     * サーバーにインストールされているプラグインの詳細情報を取得する
     *
     * @return プラグイン情報のリスト
     */
    fun getInstalledPlugins(): List<PluginInfoData>
}
