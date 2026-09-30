package party.morino.mineauth.core.web.page

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.api.config.PluginDirectory
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.web.router.auth.data.AuthorizePageModel
import java.io.File
import java.util.logging.Logger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AuthorizePageRendererのテンプレート選択のテスト
 * プラグインディレクトリの web/authorize.html の有無と内容で、使うHTMLが切り替わることを確認する
 *
 * MockBukkitは使わず、Koinにモックのプラグインと一時ディレクトリを登録する
 */
class AuthorizePageOverrideTest {
    @TempDir
    lateinit var rootDirectory: File

    // 上書き用ページであることを見分けるための目印
    private val overrideMarker = "custom-authorize-page"

    private val model = AuthorizePageModel(
        clientId = "client",
        clientName = "Sample",
        redirectUri = "https://example.com/callback",
        responseType = "code",
        state = "state",
        scope = "openid",
        scopeList = listOf("openid"),
        issuer = "https://auth.example.com",
        codeChallenge = "challenge",
        codeChallengeMethod = "S256",
        logoUrl = "/assets/lock.svg",
        applicationName = "MineAuth",
    )

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("AuthorizePageOverrideTest")
        val pluginDirectory = mockk<PluginDirectory>()
        every { pluginDirectory.getRootDirectory() } returns rootDirectory
        startKoin {
            modules(
                module {
                    single { plugin }
                    single { pluginDirectory }
                },
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /** 上書き用ページを書き込む */
    private fun writeOverride(content: String) {
        File(rootDirectory, AuthorizePageRenderer.OVERRIDE_PATH).apply {
            parentFile.mkdirs()
            writeText(content)
        }
    }

    @Test
    @DisplayName("Uses the bundled page when no override exists")
    fun usesBundledPageWithoutOverride() {
        val html = AuthorizePageRenderer.render(model)

        assertFalse(html.contains(overrideMarker))
        // 同梱ページにもビューモデルが埋め込まれている
        assertTrue(html.contains(""""clientId":"client""""))
    }

    @Test
    @DisplayName("Uses the override page when it contains the placeholder")
    fun usesOverridePage() {
        writeOverride("<title>$overrideMarker</title>${AuthorizePageRenderer.PLACEHOLDER}")

        val html = AuthorizePageRenderer.render(model)

        assertTrue(html.contains(overrideMarker))
        assertTrue(html.contains(""""clientId":"client""""))
    }

    @Test
    @DisplayName("Falls back to the bundled page when the override lacks the placeholder")
    fun fallsBackWithoutPlaceholder() {
        writeOverride("<title>$overrideMarker</title><div id=\"root\"></div>")

        val html = AuthorizePageRenderer.render(model)

        assertFalse(html.contains(overrideMarker))
    }
}
