package party.morino.mineauth.core.utils

import arrow.core.Either
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * RedirectUriValidatorのテスト
 * 改行区切りの入力の解析と、件数・形式の検証を確認する
 */
class RedirectUriValidatorTest {

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
        assertIs<Either.Left<String>>(RedirectUriValidator.parseAndValidate("\n  \n"))
    }

    @Test
    @DisplayName("Rejects input containing an invalid URI")
    fun rejectsInvalidUri() {
        val result = RedirectUriValidator.parseAndValidate("https://example.com/callback\njavascript:alert(1)")

        assertIs<Either.Left<String>>(result)
    }

    @Test
    @DisplayName("Rejects more URIs than the limit")
    fun rejectsTooManyUris() {
        val uris = (0..RedirectUriValidator.MAX_REDIRECT_URIS).map { "https://example.com/callback/$it" }

        assertIs<Either.Left<String>>(RedirectUriValidator.validateAll(uris))
    }
}
