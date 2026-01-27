package party.morino.mineauth.core.plugin

import org.koin.dsl.module
import party.morino.mineauth.core.plugin.annotation.AnnotationProcessor
import party.morino.mineauth.core.plugin.execution.DefaultMethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ParameterResolver
import party.morino.mineauth.core.plugin.route.RouteBuilder
import party.morino.mineauth.core.plugin.route.RouteExecutor

/**
 * プラグインルート登録システム用のKoinモジュール
 * Cloud (Incendo/cloud) パターンに基づくファクトリを使用した構成
 * 各コンポーネントをシングルトンとして登録する
 */
val pluginModule = module {
    // ルート管理
    single { PluginRouteRegistry() }

    // アノテーション処理
    single { AnnotationProcessor() }

    // パラメータ解決
    single { ParameterResolver() }

    // 認証・認可
    single { AuthenticationHandler() }

    // メソッド実行ハンドラーファクトリ（ファクトリパターン）
    single<MethodExecutionHandlerFactory> { DefaultMethodExecutionHandlerFactory() }

    // ルート実行（ファクトリ経由でハンドラーを取得）
    single { RouteExecutor(get(), get(), get()) }

    // ルート構築
    single { RouteBuilder(get()) }
}
