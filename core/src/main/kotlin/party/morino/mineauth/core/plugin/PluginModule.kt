package party.morino.mineauth.core.plugin

import kotlinx.serialization.json.Json
import org.koin.dsl.module
import party.morino.mineauth.core.openapi.generator.OpenApiGenerator
import party.morino.mineauth.core.openapi.generator.PathItemGenerator
import party.morino.mineauth.core.openapi.generator.SchemaGenerator
import party.morino.mineauth.core.openapi.registry.EndpointMetadataRegistry
import party.morino.mineauth.core.plugin.annotation.AnnotationProcessor
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import party.morino.mineauth.core.plugin.execution.DefaultMethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ParameterResolver
import party.morino.mineauth.core.plugin.route.RouteExecutor

/**
 * プラグインエンドポイント登録システム用のKoinモジュール
 * 各コンポーネントをシングルトンとして登録する
 */
val pluginModule = module {
    // 共有Jsonインスタンス
    // ContentNegotiationとリクエストボディのデシリアライズで同一設定を使用する
    single {
        Json {
            // nullフィールドをJSONに出力しない（OpenAPIドキュメント等で必要）
            explicitNulls = false
            // 未知のフィールドを無視
            ignoreUnknownKeys = true
        }
    }

    // アノテーション処理
    single { AnnotationProcessor() }

    // パラメータ解決
    single { ParameterResolver(get()) }

    // 認証・認可
    single { AuthenticationHandler() }

    // メソッド実行ハンドラーファクトリ（ファクトリパターン）
    single<MethodExecutionHandlerFactory> { DefaultMethodExecutionHandlerFactory() }

    // ルート実行（ファクトリ経由でハンドラーを取得）
    single { RouteExecutor(get(), get()) }

    // ライブディスパッチャ（登録済みエンドポイントの単一のデータソース）
    single { PluginEndpointDispatcher(get(), get()) }

    // 公開API実装
    single { MineAuthApiImpl() }

    // OpenAPI生成
    single { EndpointMetadataRegistry() }
    single { SchemaGenerator() }
    single { PathItemGenerator() }
    single { OpenApiGenerator() }
}
