package party.morino.mineauth.core

import io.mockk.every
import io.mockk.mockk
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
import org.mockbukkit.mockbukkit.MockBukkit
import org.mockbukkit.mockbukkit.ServerMock
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.WebServerConfigData
import party.morino.mineauth.core.mocks.config.PluginDirectoryMock
import party.morino.mineauth.core.plugin.pluginModule
import party.morino.mineauth.core.web.router.auth.oauth.TokenRouter.plugin

/**
 * MineAuthのテスト用拡張機能
 * KoinとMockBukkitのセットアップを統合する
 */
class MineAuthTest :
    BeforeAllCallback,
    AfterAllCallback {

    companion object {
        lateinit var server: ServerMock
        // テスト用リソースディレクトリ
        val testResourcesDir: File = File("src/test/resources/plugins/mineauth")
        val pluginDirectoryMock = PluginDirectoryMock()
    }

    /**
     * テスト開始前に呼び出されるメソッド
     * KoinとMockBukkitの初期化を行う
     * @param context 拡張機能のコンテキスト
     */
    override fun beforeAll(context: ExtensionContext) {
        // MockBukkitの初期化（既にモックされていればスキップ）
        if (MockBukkit.isMocked()) {
            return
        }
        server = MockBukkit.mock()

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
            single<ServerMock> { server }
        }

        // Koinを初期化（appModuleとpluginModuleを読み込む）
        getOrNull() ?: GlobalContext.startKoin {
            modules(appModule, pluginModule)
        }
    }

    /**
     * テスト終了後に呼び出されるメソッド
     * MockBukkitとKoinのクリーンアップを行う
     * @param context 拡張機能のコンテキスト
     */
    override fun afterAll(context: ExtensionContext) {
        // MockBukkitのクリーンアップ
        MockBukkit.unmock()
        // Koinのクリーンアップ
        GlobalContext.stopKoin()
    }
}
