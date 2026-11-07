package party.morino.mineauth.core.openapi.generator

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.openapi.registry.EndpointMetadataRegistry
import party.morino.mineauth.core.web.router.auth.oauth.OAuthScope

/**
 * OpenApiGeneratorのテスト
 */
class OpenApiGeneratorTest {

    @BeforeEach
    fun setUp() {
        // 他のテストが残したKoinの影響を受けないよう、起動前に停止しておく
        stopKoin()
        startKoin {
            modules(
                module {
                    single<MineAuth> { mockk(relaxed = true) }
                    single { EndpointMetadataRegistry() }
                    single { MineAuthConfig() }
                }
            )
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    @Test
    @DisplayName("The oauth2 flow lists every scope the server accepts")
    fun oauth2FlowListsEveryScope() {
        val securitySchemes = OpenApiGenerator().generate().components?.securitySchemes
        val scopes = securitySchemes?.get("oauth2")?.flows?.authorizationCode?.scopes

        assertEquals(OAuthScope.entries.map { it.value }.toSet(), scopes?.keys)
    }
}
