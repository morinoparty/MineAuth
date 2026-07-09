package party.morino.mineauth.core.plugin.dispatch

import party.morino.mineauth.core.plugin.annotation.EndpointMetadata

/**
 * 1つの名前空間に登録されたエンドポイント群を保持するテーブル
 * ディスパッチャのConcurrentHashMapの値としてアトミックに差し替えられる
 *
 * @property pluginName 名前空間を所有するプラグイン名
 * @property basePath マウントされたベースパス（例: /api/v1/plugins/vault）
 * @property endpoints コンパイル済みエンドポイントのリスト
 */
data class NamespaceTable(
    val pluginName: String,
    val basePath: String,
    val endpoints: List<EndpointMetadata>
)
