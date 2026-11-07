package party.morino.mineauth.core.openapi.generator

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * CorePathsGeneratorの流量制限の記載のテスト
 */
class CorePathsRateLimitTest {

    @Test
    @DisplayName("Token, revocation and introspection endpoints document the 429 response")
    fun limitedEndpointsDocument429() {
        val paths = CorePathsGenerator().generate()

        listOf("/oauth2/token", "/oauth2/revoke", "/oauth2/introspect").forEach { path ->
            assertNotNull(paths.getValue(path).post?.responses?.get("429"), path)
        }
    }
}
