package party.morino.mineauth.core.http

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.http.ConditionalRequest

/**
 * [ConditionalRequest.isNoneMatch]のRFC 7232準拠の照合テスト
 */
class ConditionalRequestTest {

    @Test
    @DisplayName("Empty If-None-Match never matches")
    fun emptyNeverMatches() {
        assertFalse(ConditionalRequest.fromHeaderValues(emptyList()).isNoneMatch("\"v1\""))
    }

    @Test
    @DisplayName("Exact etag matches")
    fun exactMatch() {
        assertTrue(ConditionalRequest.fromHeaderValues(listOf("\"v1\"")).isNoneMatch("\"v1\""))
    }

    @Test
    @DisplayName("Non-matching etag does not match")
    fun noMatch() {
        assertFalse(ConditionalRequest.fromHeaderValues(listOf("\"v1\"")).isNoneMatch("\"v2\""))
    }

    @Test
    @DisplayName("Wildcard matches any etag")
    fun wildcardMatches() {
        assertTrue(ConditionalRequest.fromHeaderValues(listOf("*")).isNoneMatch("\"anything\""))
    }

    @Test
    @DisplayName("Comma-separated list matches any member")
    fun commaListMatches() {
        val cond = ConditionalRequest.fromHeaderValues(listOf("\"a\", \"b\", \"c\""))
        assertTrue(cond.isNoneMatch("\"b\""))
        assertFalse(cond.isNoneMatch("\"z\""))
    }

    @Test
    @DisplayName("Weak validators match by weak comparison")
    fun weakComparison() {
        // 条件付きGETでは弱いバリデータ同士・強弱混在を弱い比較で一致とみなす
        assertTrue(ConditionalRequest.fromHeaderValues(listOf("W/\"v1\"")).isNoneMatch("\"v1\""))
        assertTrue(ConditionalRequest.fromHeaderValues(listOf("\"v1\"")).isNoneMatch("W/\"v1\""))
    }
}
