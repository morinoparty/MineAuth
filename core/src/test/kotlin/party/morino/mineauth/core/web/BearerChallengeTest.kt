package party.morino.mineauth.core.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * BearerChallengeのテスト
 * RFC 6750 Section 3 のヘッダー文法に反する文字がヘッダーへ出力されないことを確認する
 */
class BearerChallengeTest {

    @Test
    @DisplayName("Description keeps only the characters RFC 6750 allows")
    fun descriptionKeepsOnlyAllowedCharacters() {
        val header = BearerChallenge.headerValue(
            realm = "MineAuth",
            error = BearerChallenge.ErrorCode.INVALID_TOKEN,
            errorDescription = "bad \"token\"\\\r\n期限切れ!",
        )

        assertTrue(header.endsWith("error_description=\"bad token!\""), header)
    }
}
