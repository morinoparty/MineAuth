package party.morino.mineauth.core.web.components.plugin

import kotlinx.serialization.Serializable

/**
 * GET /api/v1/plugins/availableIntegrations のレスポンス
 *
 * MineAuthコアに内蔵された連携（LuckPermsなど）と、
 * アドオンがMineAuthApi経由で動的に登録したエンドポイント群の両方を返す
 *
 * @property integrations 利用可能なコア内蔵連携の名前一覧
 * @property plugins 現在マウントされているアドオンの名前空間ごとの登録情報
 */
@Serializable
data class AvailableIntegrationsResponse(
    val integrations: List<String>,
    val plugins: List<RegisteredPluginData>,
)
