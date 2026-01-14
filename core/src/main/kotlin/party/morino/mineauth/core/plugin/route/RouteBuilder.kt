package party.morino.mineauth.core.plugin.route

import io.ktor.server.auth.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.plugin.PluginContext
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.web.JwtCompleteCode

/**
 * EndpointMetadataからKtorルートを動的に生成するビルダー
 * アノテーション処理の結果からKtorのルーティングを構築する
 */
class RouteBuilder(
    private val executor: RouteExecutor
) : KoinComponent {

    /**
     * プラグインコンテキストとメタデータリストからルートを構築する
     *
     * @param route Ktorのルートコンテキスト
     * @param context プラグインコンテキスト
     * @param endpoints エンドポイントメタデータのリスト
     */
    fun buildRoutes(
        route: Route,
        context: PluginContext,
        endpoints: List<EndpointMetadata>
    ) {
        // プラグインの基本パス配下にルートを作成
        route.route(context.basePath) {
            for (endpoint in endpoints) {
                registerEndpoint(this, endpoint)
            }
        }
    }

    /**
     * 単一エンドポイントをルートに登録する
     *
     * @param route Ktorのルートコンテキスト
     * @param endpoint エンドポイントメタデータ
     */
    private fun registerEndpoint(route: Route, endpoint: EndpointMetadata) {
        // パスを正規化（:id形式を{id}形式に変換）
        val normalizedPath = normalizePath(endpoint.path)

        if (endpoint.requiresAuthentication) {
            // 認証が必要な場合はauthenticateブロック内に配置
            route.authenticate(JwtCompleteCode.USER_TOKEN.code) {
                registerHttpMethod(this, normalizedPath, endpoint)
            }
        } else {
            // 認証不要の場合は直接登録
            registerHttpMethod(route, normalizedPath, endpoint)
        }
    }

    /**
     * HTTPメソッドに応じたルートを登録する
     *
     * @param route Ktorのルートコンテキスト
     * @param path 正規化されたパス
     * @param endpoint エンドポイントメタデータ
     */
    private fun registerHttpMethod(route: Route, path: String, endpoint: EndpointMetadata) {
        // ハンドラー関数を作成
        val handler: suspend RoutingContext.() -> Unit = {
            executor.execute(call, endpoint)
        }

        // HTTPメソッドに応じてルートを登録
        when (endpoint.httpMethod) {
            HttpMethodType.GET -> route.get(path, handler)
            HttpMethodType.POST -> route.post(path, handler)
            HttpMethodType.PUT -> route.put(path, handler)
            HttpMethodType.DELETE -> route.delete(path, handler)
            HttpMethodType.PATCH -> route.patch(path, handler)
        }
    }

    /**
     * パスパラメータの形式を正規化する
     * :id -> {id} に変換（Ktor形式）
     *
     * @param path 変換前のパス
     * @return Ktor形式に変換されたパス
     */
    private fun normalizePath(path: String): String {
        return path.replace(Regex(":([a-zA-Z_][a-zA-Z0-9_]*)"), "{$1}")
    }
}
