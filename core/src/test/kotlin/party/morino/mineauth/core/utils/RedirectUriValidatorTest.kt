package party.morino.mineauth.core.utils

import arrow.core.Either
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * RedirectUriValidatorのテスト
 * 改行区切りの入力の解析と、件数・形式・登録規則の検証を確認する
 */
class RedirectUriValidatorTest {

    // 全ての値が受け付けられることを確認する
    private fun assertAccepted(vararg uris: String) {
        uris.forEach { uri -> assertEquals(Either.Right(uri), RedirectUriValidator.validate(uri)) }
    }

    // 全ての値が同じ種類の理由で拒否されることを確認する
    private inline fun <reified E : RedirectUriError> assertRejected(vararg uris: String) {
        uris.forEach { uri ->
            val result = RedirectUriValidator.validate(uri)
            assertIs<Either.Left<RedirectUriError>>(result, uri)
            assertIs<E>(result.value, uri)
        }
    }

    @Test
    @DisplayName("Parses one URI per line and removes blanks and duplicates")
    fun parsesLinesAndRemovesBlanksAndDuplicates() {
        val input = " https://example.com/callback \n\nhttp://localhost:3000/callback\nhttps://example.com/callback\n"

        val result = RedirectUriValidator.parseAndValidate(input)

        assertEquals(
            Either.Right(listOf("https://example.com/callback", "http://localhost:3000/callback")),
            result
        )
    }

    @Test
    @DisplayName("Rejects empty input")
    fun rejectsEmptyInput() {
        assertEquals(Either.Left(RedirectUriError.Empty), RedirectUriValidator.parseAndValidate("\n  \n"))
    }

    @Test
    @DisplayName("Reports the reason of the first invalid URI in the input")
    fun reportsReasonOfFirstInvalidUri() {
        val result = RedirectUriValidator.parseAndValidate("https://example.com/callback\njavascript:alert(1)")

        assertEquals(Either.Left(RedirectUriError.ForbiddenScheme("javascript:alert(1)", "javascript")), result)
    }

    @Test
    @DisplayName("Rejects more URIs than the limit")
    fun rejectsTooManyUris() {
        val uris = (0..RedirectUriValidator.MAX_REDIRECT_URIS).map { "https://example.com/callback/$it" }

        assertEquals(
            Either.Left(RedirectUriError.TooMany(RedirectUriValidator.MAX_REDIRECT_URIS)),
            RedirectUriValidator.validateAll(uris)
        )
    }

    @Test
    @DisplayName("Rejects http for non-loopback hosts")
    fun rejectsHttpForNonLoopbackHosts() {
        assertRejected<RedirectUriError.InsecureHttp>(
            "http://app.example.com/callback",
            "http://localhost.evil.example/callback",
            "http://localhost@evil.example/callback"
        )
    }

    @Test
    @DisplayName("Rejects loopback http that also matches other hosts as a regex")
    fun rejectsLoopbackHttpThatMatchesOtherHostsAsRegex() {
        // いずれもURIとしては正しいが、認可時に正規表現として評価すると http://localhost.evil.example/ などに一致する
        assertRejected<RedirectUriError.UnsafeLoopbackPattern>(
            "http://localhost?.*",
            "http://localhost/?.*",
            "http://localhost/*.evil.example/cb",
            "http://localhost:3?.*"
        )
    }

    @Test
    @DisplayName("Rejects loopback http with userinfo")
    fun rejectsLoopbackHttpWithUserInfo() {
        // URIとしてはホストがループバックになるが、正規表現として評価すると http://evil.example/@localhost/ に一致する
        assertRejected<RedirectUriError.HttpUserInfo>(
            "http://.*@localhost/",
            "http://.+@127.0.0.1:8080/cb",
            "http://(.*)@[::1]/cb",
            "HTTP://.*@localhost/",
            "http://user@localhost:3000/cb"
        )
        // ユーザー情報のない通常のループバックURIは引き続き登録できる
        assertAccepted("http://localhost:3000/cb", "http://127.0.0.1/cb")
    }

    @Test
    @DisplayName("Always rejects dangerous schemes")
    fun rejectsDangerousSchemes() {
        assertRejected<RedirectUriError.ForbiddenScheme>(
            "javascript:alert(1)",
            "javascript://x/%0aalert(1)",
            "JavaScript://x/alert(1)",
            "data:text/html,x",
            "file:///etc/passwd",
            "vbscript:msgbox(1)"
        )
    }

    @Test
    @DisplayName("Accepts private-use schemes only in reverse-domain form with an authority")
    fun acceptsPrivateUseSchemesInReverseDomainForm() {
        assertAccepted("com.example.app://callback", "com.example.app://callback/path")
        assertRejected<RedirectUriError.UnsupportedScheme>("myapp://callback", "ftp://example.com/callback")
        // URLBuilderが書き換えてしまう形式は受け付けない（OAuthValidationTestで書き換えを確認している）
        assertRejected<RedirectUriError.UnsupportedPrivateUseForm>(
            "com.example.app:/oauth/callback",
            "com.example.app:callback",
            "com.example.app:///callback",
            "Com.Example.App://callback"
        )
    }

    @Test
    @DisplayName("Rejects http patterns that are not fixed to a loopback host")
    fun rejectsUnsafeHttpPatterns() {
        assertRejected<RedirectUriError.UnsafeLoopbackPattern>(
            "http://public\\.example\\.com/.*",
            "http://localhost\\.evil\\.example/callback",
            "http://localhost:.*/callback",
            "http://localhost:\\d+.*",
            "http://localhost/?.*\\d",
            "http://localhost(\\.evil\\.example)?/callback",
            // エスケープしていない . や [] はリテラルのホストではない
            "http://127.0.0.1:\\d+/callback",
            "http://[::1]:\\d+/callback"
        )
    }

    @Test
    @DisplayName("Rejects top-level alternation that escapes the scheme check")
    fun rejectsTopLevelAlternation() {
        assertRejected<RedirectUriError.TopLevelAlternation>(
            "https://a\\.example\\.com|javascript:.*",
            "https://a\\.example\\.com|http://evil\\.example/.*",
            "https://a\\.example\\.com|",
            "http://localhost:\\d+/callback|http://evil\\.example/.*",
            // 文字列の走査ではグループの内側に見える書き方でも検出する
            "https://a\\Q(\\E|javascript:.*",
            "https://a[](]|javascript:.*"
        )
    }

    @Test
    @DisplayName("Rejects an invalid URI or exceeding the limit when adding")
    fun rejectsInvalidAddition() {
        val registered = listOf("https://example.com/callback")
        val full = (1..RedirectUriValidator.MAX_REDIRECT_URIS).map { "https://example.com/callback/$it" }

        assertEquals(
            Either.Left(RedirectUriError.InsecureHttp("http://example.com/new")),
            RedirectUriValidator.validateAddition(registered, "http://example.com/new")
        )
        assertEquals(
            Either.Left(RedirectUriError.TooMany(RedirectUriValidator.MAX_REDIRECT_URIS)),
            RedirectUriValidator.validateAddition(full, "https://example.com/new")
        )
    }
}
