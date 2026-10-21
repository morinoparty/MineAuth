package party.morino.mineauth.core.file.data

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

/**
 * JWTConfigDataのテスト
 * 受け付けるissuerの一覧が正しく構築されることを確認する
 */
class JWTConfigDataTest {

    @Test
    @DisplayName("Accepted issuers start with own issuer and include trusted issuers")
    fun acceptedIssuersIncludeTrustedIssuers() {
        val config = JWTConfigData(
            issuer = "https://api.example.com/main",
            keyId = UUID.randomUUID(),
            trustedIssuers = listOf("https://api.example.com/lobby", "https://api.example.com/res")
        )

        assertEquals(
            listOf("https://api.example.com/main", "https://api.example.com/lobby", "https://api.example.com/res"),
            config.acceptedIssuers
        )
    }

    @Test
    @DisplayName("Accepted issuers are normalized and deduplicated")
    fun acceptedIssuersAreNormalizedAndDeduplicated() {
        val config = JWTConfigData(
            issuer = "https://api.example.com/main",
            keyId = UUID.randomUUID(),
            // 自サーバーと同じ値を末尾スラッシュ付きで書いても重複しない
            trustedIssuers = listOf("https://api.example.com/main/", "https://api.example.com/lobby/")
        )

        assertEquals(
            listOf("https://api.example.com/main", "https://api.example.com/lobby"),
            config.acceptedIssuers
        )
    }
}
