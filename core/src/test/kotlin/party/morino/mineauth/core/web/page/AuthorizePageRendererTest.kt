package party.morino.mineauth.core.web.page

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.router.auth.data.AuthorizePageModel
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AuthorizePageRendererのテスト
 * ビューモデルの埋め込みとエスケープの純粋関数をテストする
 */
class AuthorizePageRendererTest {
    private val template = "<head>${AuthorizePageRenderer.PLACEHOLDER}</head><body></body>"

    private fun model(clientName: String = "Sample", nonce: String? = null) = AuthorizePageModel(
        clientId = "client",
        clientName = clientName,
        redirectUri = "https://example.com/callback",
        responseType = "code",
        state = "state",
        scope = "openid profile",
        scopeList = listOf("openid", "profile"),
        issuer = "https://auth.example.com",
        codeChallenge = "challenge",
        codeChallengeMethod = "S256",
        logoUrl = "/assets/lock.svg",
        applicationName = "MineAuth",
        nonce = nonce,
    )

    /** 埋め込まれたJSONを取り出す */
    private fun embeddedJson(html: String): String =
        html.substringAfter("""type="application/json">""").substringBefore("</script>")

    @Test
    @DisplayName("Embeds the model as JSON")
    fun embedsModel() {
        val html = AuthorizePageRenderer.render(template, model(nonce = "n-1"))
        val json = Json.parseToJsonElement(embeddedJson(html)).jsonObject

        assertFalse(html.contains("__MINEAUTH_MODEL__"))
        assertEquals("client", json["clientId"]?.jsonPrimitive?.content)
        assertEquals("n-1", json["nonce"]?.jsonPrimitive?.content)
    }

    @Test
    @DisplayName("Omits nonce when absent")
    fun omitsNullNonce() {
        val json = Json.parseToJsonElement(embeddedJson(AuthorizePageRenderer.render(template, model()))).jsonObject

        assertFalse(json.containsKey("nonce"))
    }

    @Test
    @DisplayName("Script-breaking characters are escaped")
    fun escapesScriptBreakingCharacters() {
        val malicious = "</script><script>alert(1)</script>& "
        val html = AuthorizePageRenderer.render(template, model(clientName = malicious))
        val embedded = embeddedJson(html)

        // 埋め込んだJSONの中に生の < > & が残らない
        assertFalse(embedded.contains('<') || embedded.contains('>') || embedded.contains('&'))
        // ただしJSONとしては元の文字列に戻る
        assertEquals(malicious, Json.parseToJsonElement(embedded).jsonObject["clientName"]?.jsonPrimitive?.content)
        assertTrue(html.endsWith("</head><body></body>"))
    }

    @Test
    @DisplayName("Accepts an override page that contains the placeholder")
    fun acceptsOverrideWithPlaceholder() {
        assertEquals(template, AuthorizePageRenderer.validateOverride(template).getOrNull())
    }

    @Test
    @DisplayName("Rejects an override page without the placeholder")
    fun rejectsOverrideWithoutPlaceholder() {
        // プレースホルダを書き換えてしまったHTMLは、ビューモデルを埋め込めないので使わない
        val broken = template.replace("__MINEAUTH_MODEL__", "{}")

        assertTrue(AuthorizePageRenderer.validateOverride(broken).isLeft())
    }
}
