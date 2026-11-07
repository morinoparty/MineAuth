package party.morino.mineauth.core.file.data

import arrow.core.getOrElse
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * server.baseUrlの検証のテスト
 * 公開できないissuerは読み込みエラーになり、httpsでないhostは警告対象になることを確認する
 */
class BaseUrlValidationTest {

    /**
     * 検証に失敗するbaseUrlのエラーを返す
     */
    private fun errorOf(baseUrl: String): ConfigError =
        ServerConfig(baseUrl = baseUrl).validateBaseUrl().swap().getOrElse { error("Expected $baseUrl to be rejected") }

    /**
     * 検証に成功するbaseUrlのURIを返す
     */
    private fun uriOf(baseUrl: String): URI =
        ServerConfig(baseUrl = baseUrl).validateBaseUrl().getOrElse { error("Rejected $baseUrl: ${it.message}") }

    @Test
    @DisplayName("Accepts https with a path prefix and does not warn")
    fun acceptsHttps() {
        val uri = uriOf("https://api.example.com/main/")

        assertEquals("api.example.com", uri.host)
        assertFalse(isInsecureBaseUrl(uri))
    }

    @Test
    @DisplayName("Does not warn about http on loopback hosts")
    fun loopbackHttpIsNotInsecure() {
        val loopbackUrls = listOf(
            "http://localhost:8080",
            "http://LOCALHOST",
            "http://app.localhost",
            "http://127.0.0.1:8080",
            "http://127.1.2.3",
            "http://[::1]:8080",
        )

        loopbackUrls.forEach { assertFalse(isInsecureBaseUrl(uriOf(it)), it) }
    }

    @Test
    @DisplayName("Warns about http and other schemes on public hosts")
    fun publicNonHttpsIsInsecure() {
        val insecureUrls = listOf(
            "http://api.example.com",
            "ftp://api.example.com",
            // ループバックに見えるだけの公開ホスト名
            "http://localhost.example.com",
            "http://127.0.0.1.example.com",
            // ローカルネットワークのアドレスはループバックではない
            "http://192.168.0.10:8080",
        )

        insecureUrls.forEach { assertTrue(isInsecureBaseUrl(uriOf(it)), it) }
    }

    @Test
    @DisplayName("Accepts a host name with an underscore")
    fun acceptsUnderscoreHost() {
        // java.net.URIはhostをnullにするが、Dockerのサービス名などで実在するホスト名
        val uri = uriOf("http://mine_auth:8080/main")

        assertTrue(isInsecureBaseUrl(uri))
        assertFalse(isInsecureBaseUrl(uriOf("https://mine_auth:8080/main")))
    }

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
    @DisplayName("Rejects unparsable URLs")
    fun rejectsUnparsable() {
        assertIs<ConfigError.BaseUrlUnparsable>(errorOf("https://exa mple.com"))
        assertIs<ConfigError.BaseUrlUnparsable>(errorOf("https://"))
        // ホストはあるがスキームがない
        assertIs<ConfigError.BaseUrlUnparsable>(errorOf("//api.example.com"))
    }

    @Test
    @DisplayName("Rejects URLs without a host")
    fun rejectsMissingHost() {
        listOf("", "api.example.com", "localhost:8080", "file:///srv/mineauth", "https://:8443").forEach {
            assertIs<ConfigError.BaseUrlMissingHost>(errorOf(it), it)
        }
    }
}
