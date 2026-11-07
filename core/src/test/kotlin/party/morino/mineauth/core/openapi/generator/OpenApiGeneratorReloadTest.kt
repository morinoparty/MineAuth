package party.morino.mineauth.core.openapi.generator

import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.loadKoinModules
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.plugin.pluginModule
import java.util.logging.Logger
import kotlin.test.assertEquals

/**
 * /mineauth reload 後のOpenAPIドキュメントのテスト
 * シングルトンのジェネレーターが、再読み込みしたbaseUrlでドキュメントを生成することを確認する
 */
class OpenApiGeneratorReloadTest {

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("OpenApiGeneratorReloadTest")
        startKoin {
            modules(module { single { plugin } }, pluginModule)
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /**
     * ConfigLoaderと同じく、設定の登録を置き換える
     */
    private fun registerBaseUrl(baseUrl: String) {
        loadKoinModules(module { single { MineAuthConfig(server = ServerConfig(baseUrl = baseUrl)) } })
    }

    @Test
    @DisplayName("Server URL and OAuth endpoints follow baseUrl after reload")
    fun documentFollowsReload() {
        val generator = GlobalContext.get().get<OpenApiGenerator>()
        registerBaseUrl("https://first.example.com")
        assertEquals("https://first.example.com", generator.generate().servers.single().url)

        registerBaseUrl("https://second.example.com")
        val document = generator.generate()

        assertEquals("https://second.example.com", document.servers.single().url)
        assertEquals(
            "https://second.example.com/oauth2/token",
            document.components?.securitySchemes?.get("oauth2")?.flows?.authorizationCode?.tokenUrl
        )
    }
}
