package party.morino.mineauth.core.file.data

import arrow.core.getOrElse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertIs

/**
 * server.baseUrlの検証のテスト
 * 公開できないissuerは読み込みエラーになることを確認する
 */
class BaseUrlValidationTest {

    /**
     * 検証に失敗するbaseUrlのエラーを返す
     */
    private fun errorOf(baseUrl: String): ConfigError =
        ServerConfig(baseUrl = baseUrl).validateBaseUrl().swap().getOrElse { error("Expected $baseUrl to be rejected") }

    @Test
    @DisplayName("Rejects a query including an empty one")
    fun rejectsQuery() {
        assertIs<ConfigError.BaseUrlHasQuery>(errorOf("https://api.example.com?tenant=a"))
        assertIs<ConfigError.BaseUrlHasQuery>(errorOf("https://api.example.com/?"))
    }

    @Test
    @DisplayName("Rejects a fragment including an empty one")
    fun rejectsFragment() {
        assertIs<ConfigError.BaseUrlHasFragment>(errorOf("https://api.example.com#top"))
        assertIs<ConfigError.BaseUrlHasFragment>(errorOf("https://api.example.com/#"))
    }

    @Test
    @DisplayName("Rejects URLs without a host")
    fun rejectsMissingHost() {
        listOf("", "api.example.com", "localhost:8080", "file:///srv/mineauth", "https://:8443").forEach {
            assertIs<ConfigError.BaseUrlMissingHost>(errorOf(it), it)
        }
    }
}
