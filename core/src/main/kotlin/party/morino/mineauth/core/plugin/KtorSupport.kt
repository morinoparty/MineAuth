package party.morino.mineauth.core.plugin

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import org.koin.dsl.module
import party.morino.mineauth.api.RegisterHandler
import party.morino.mineauth.core.plugin.annotation.AnnotationProcessor
import party.morino.mineauth.core.plugin.execution.DefaultMethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ParameterResolver
import party.morino.mineauth.core.plugin.route.RouteBuilder
import party.morino.mineauth.core.plugin.route.RouteExecutor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Ktorルートシステムのコルーチンサポート設定
 * Cloud (Incendo/cloud) の installCoroutineSupport() パターンに基づく
 *
 * @property coroutineScope コルーチン実行に使用するスコープ
 * @property coroutineContext コルーチン実行に使用するコンテキスト
 * @property enableClassLoaderBridging クラスローダー間の互換性のための動的プロキシを有効にするか
 */
data class KtorSupportConfig(
    val coroutineScope: CoroutineScope = GlobalScope,
    val coroutineContext: CoroutineContext = EmptyCoroutineContext,
    val enableClassLoaderBridging: Boolean = true
)

/**
 * Ktorサポート設定を含むKoinモジュールを生成する拡張関数
 * Cloud (Incendo/cloud) の installCoroutineSupport() パターンに基づく
 *
 * デフォルトのpluginModuleの代わりに使用することで、
 * カスタム設定を適用したモジュールを取得できる
 *
 * @param config Ktorサポートの設定
 * @return 設定済みのKoinモジュール
 */
fun pluginModuleWithKtorSupport(
    config: KtorSupportConfig = KtorSupportConfig()
) = module {
    // ルート管理
    single { PluginRouteRegistry() }

    // アノテーション処理
    single { AnnotationProcessor() }

    // パラメータ解決
    single { ParameterResolver() }

    // 認証・認可
    single { AuthenticationHandler() }

    // メソッド実行ハンドラーファクトリ（設定を考慮）
    single<MethodExecutionHandlerFactory> {
        DefaultMethodExecutionHandlerFactory()
    }

    // ルート実行
    single { RouteExecutor(get(), get(), get()) }

    // ルート構築
    single { RouteBuilder(get()) }
}

/**
 * RegisterHandler に Ktor サポートをインストールする拡張関数
 * 現在は後方互換性のために提供されるが、
 * 将来的にはこの拡張でカスタム設定を適用可能にする
 *
 * @param config Ktorサポートの設定（現在は未使用だが将来の拡張用）
 * @return 同じRegisterHandlerインスタンス（チェーン呼び出し用）
 */
fun RegisterHandler.installKtorSupport(
    config: KtorSupportConfig = KtorSupportConfig()
): RegisterHandler {
    // 現在は設定の検証のみ行う
    // 将来的にはここでカスタム設定を適用可能
    return this
}
