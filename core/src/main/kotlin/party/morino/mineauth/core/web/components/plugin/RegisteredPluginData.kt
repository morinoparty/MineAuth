package party.morino.mineauth.core.web.components.plugin

import kotlinx.serialization.Serializable

/**
 * MineAuthApi.register()で登録された1つの名前空間の公開情報
 *
 * @property namespace URL名前空間（例: tickets）
 * @property plugin 名前空間を所有するプラグイン名
 * @property basePath マウントされたベースパス（例: /api/v1/plugins/tickets）
 * @property endpoints この名前空間に登録されたエンドポイントの一覧
 */
@Serializable
data class RegisteredPluginData(
    val namespace: String,
    val plugin: String,
    val basePath: String,
    val endpoints: List<RegisteredEndpointData>,
)
