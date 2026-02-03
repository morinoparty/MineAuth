package party.morino.mineauth.core.openapi.registry

import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import java.util.concurrent.ConcurrentHashMap

/**
 * 登録されたプラグインのエンドポイント情報
 *
 * @property basePath プラグインのベースパス（例: /api/v1/plugins/vault）
 * @property endpoints エンドポイントメタデータのリスト
 */
data class RegisteredPluginEndpoints(
    val basePath: String,
    val endpoints: List<EndpointMetadata>
)

/**
 * 登録された全エンドポイントのメタデータを管理するレジストリ
 * OpenAPI仕様生成のためのデータソースとして機能する
 *
 * 外部プラグインが登録したエンドポイント情報を集約し、
 * OpenAPIドキュメント生成時に参照可能にする
 */
class EndpointMetadataRegistry : KoinComponent {

    // プラグイン名 -> 登録済みエンドポイント情報のマップ
    // スレッドセーフなConcurrentHashMapを使用
    private val registeredEndpoints = ConcurrentHashMap<String, RegisteredPluginEndpoints>()

    /**
     * プラグインのエンドポイントを登録する
     *
     * @param pluginName プラグイン名（一意の識別子として使用）
     * @param basePath プラグインのベースパス
     * @param endpoints エンドポイントメタデータのリスト
     */
    fun register(pluginName: String, basePath: String, endpoints: List<EndpointMetadata>) {
        registeredEndpoints[pluginName] = RegisteredPluginEndpoints(
            basePath = basePath,
            endpoints = endpoints
        )
    }

    /**
     * プラグインのエンドポイントを登録解除する
     *
     * @param pluginName 登録解除するプラグイン名
     */
    fun unregister(pluginName: String) {
        registeredEndpoints.remove(pluginName)
    }

    /**
     * 登録された全エンドポイントを取得する
     * OpenAPI生成時に使用される
     *
     * @return プラグイン名とエンドポイント情報のマップ（イミュータブル）
     */
    fun getAllEndpoints(): Map<String, RegisteredPluginEndpoints> {
        return registeredEndpoints.toMap()
    }

    /**
     * 登録されているプラグイン名の一覧を取得する
     *
     * @return プラグイン名のリスト
     */
    fun getRegisteredPlugins(): List<String> {
        return registeredEndpoints.keys().toList()
    }
}
