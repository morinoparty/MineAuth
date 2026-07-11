package party.morino.mineauth.core.http

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.http.CacheControl

/**
 * [CacheControl]のヘッダー文字列化のテスト
 */
class CacheControlTest {

    @Test
    @DisplayName("maxAge renders private by default")
    fun maxAgePrivate() {
        assertEquals("private, max-age=60", CacheControl.maxAge(60).toHeaderValue())
    }

    @Test
    @DisplayName("maxAge renders public when requested")
    fun maxAgePublic() {
        assertEquals(
            "public, max-age=300",
            CacheControl.maxAge(300, CacheControl.Visibility.PUBLIC).toHeaderValue()
        )
    }

    @Test
    @DisplayName("no-store is rendered alone")
    fun noStoreAlone() {
        assertEquals("no-store", CacheControl.NoStore.toHeaderValue())
    }

    @Test
    @DisplayName("no-cache and must-revalidate are combined")
    fun combinedDirectives() {
        val cc = CacheControl(
            visibility = CacheControl.Visibility.PUBLIC,
            maxAgeSeconds = 10,
            noCache = true,
            mustRevalidate = true
        )
        assertEquals("public, no-cache, max-age=10, must-revalidate", cc.toHeaderValue())
    }
}
