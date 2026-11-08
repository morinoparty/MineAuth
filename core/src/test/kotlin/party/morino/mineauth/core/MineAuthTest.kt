package party.morino.mineauth.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import java.util.UUID
import java.util.logging.Logger
import kotlin.jvm.java
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.GlobalContext.getOrNull
import org.koin.dsl.module
import org.bukkit.Bukkit
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.ObservabilityConfig
import party.morino.mineauth.core.file.data.WebServerConfigData
import party.morino.mineauth.core.web.router.auth.oauth.RedeemedCodeStore
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.mocks.config.PluginDirectoryMock
import party.morino.mineauth.core.plugin.pluginModule
import party.morino.mineauth.core.utils.Argon2Gate
import party.morino.mineauth.core.web.ratelimit.RateLimitTestSupport
import party.morino.mineauth.core.web.router.auth.oauth.RefreshTokenResponseCache
import party.morino.mineauth.core.web.router.auth.oauth.TokenStateValidator

/**
 * MineAuthのテスト用拡張機能
 * KoinとBukkitのMockKスタブのセットアップを統合する
 */
class MineAuthTest :
    BeforeAllCallback,
    AfterAllCallback {

    companion object {
        // テスト用リソースディレクトリ
        val testResourcesDir: File = File("src/test/resources/plugins/mineauth")
        val pluginDirectoryMock = PluginDirectoryMock()
    }

    /**
     * テスト開始前に呼び出されるメソッド
     * KoinとBukkitスタブの初期化を行う
     * @param context 拡張機能のコンテキスト
     */
    override fun beforeAll(context: ExtensionContext) {
        // Bukkit.getOnlinePlayers()などの静的呼び出しをMockKで差し替える（オンラインプレイヤーは空）
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns emptyList()

        // MineAuthをモック（プラグインのロードをスキップ）
        val mockPlugin = mockk<MineAuth>(relaxed = true)
        every { mockPlugin.dataFolder } returns pluginDirectoryMock.getRootDirectory()
        every { mockPlugin.logger } returns Logger.getLogger("MineAuthTest")

        // Koinの初期化
        val appModule = module {
            single<MineAuth> { mockPlugin }
            single<PluginDirectory> { pluginDirectoryMock }
            single { WebServerConfigData(port = 8080, ssl = null) }
            single {
                JWTConfigData(
                    issuer = "https://test.example.com",
                    realm = "test-realm",
                    privateKeyFile = "privateKey.pem",
                    keyId = UUID.randomUUID()
                )
            }
            single {
                MineAuthConfig(
                    // テスト用のデフォルト設定（トレーシングは無効）
                    observability = ObservabilityConfig(enabled = false)
                )
            }
            // リフレッシュトークンローテーションのreuse grace period用キャッシュ
            single { RefreshTokenResponseCache() }
            // 認可コードのリプレイ検知用の使用済み記録
            single { RedeemedCodeStore() }
            // Application.module()のBearer認証が参照するVerifierと状態検証（鍵はテスト用に生成したもの）
            single { JwtTestSupport.verifiers }
            single { TokenStateValidator() }
            // 本番ではConfigLoaderが登録する流量制限（テストでは無効にしておく）
            single { RateLimitTestSupport.disabled() }
            // Argon2の同時実行数を制限するゲート（本番ではMineAuth.setupKoinが登録する）
            single { Argon2Gate() }
        }

        // Koinを初期化（appModuleとpluginModuleを読み込む）
        getOrNull() ?: GlobalContext.startKoin {
            modules(appModule, pluginModule)
        }
    }

    /**
     * テスト終了後に呼び出されるメソッド
     * BukkitスタブとKoinのクリーンアップを行う
     * @param context 拡張機能のコンテキスト
     */
    override fun afterAll(context: ExtensionContext) {
        // Bukkitの静的モックを解除
        unmockkStatic(Bukkit::class)
        // Koinのクリーンアップ
        GlobalContext.stopKoin()
    }
}
