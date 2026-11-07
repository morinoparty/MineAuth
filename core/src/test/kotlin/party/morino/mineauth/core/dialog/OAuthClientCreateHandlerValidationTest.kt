package party.morino.mineauth.core.dialog

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * クライアント作成ダイアログの入力値検証のテスト
 * トークンイントロスペクションの許可が、権限を持つ実行者とConfidentialクライアントの組み合わせでのみ受け付けられることを確認する（Issue #480）
 */
class OAuthClientCreateHandlerValidationTest {

    private val redirectUri = "https://example.com/callback"

    @Test
    @DisplayName("Allowing introspection on a public client is a validation error")
    fun publicClientCannotAllowIntrospection() {
        val result = OAuthClientCreateHandler.validate(
            "Client",
            "public",
            redirectUri,
            introspectionAllowed = true,
            canGrantIntrospection = true
        )

        assertTrue(result.leftOrNull().orEmpty().contains("Confidential"))
    }

    @Test
    @DisplayName("Allowing introspection on a confidential client passes validation")
    fun confidentialClientCanAllowIntrospection() {
        val result = OAuthClientCreateHandler.validate(
            "Client",
            "confidential",
            redirectUri,
            introspectionAllowed = true,
            canGrantIntrospection = true
        )

        assertEquals(listOf(redirectUri), result.getOrNull())
    }

    @Test
    @DisplayName("Allowing introspection without the permission node is a validation error")
    fun creatorWithoutPermissionCannotAllowIntrospection() {
        val result = OAuthClientCreateHandler.validate(
            "Client",
            "confidential",
            redirectUri,
            introspectionAllowed = true,
            canGrantIntrospection = false
        )

        assertTrue(result.leftOrNull().orEmpty().contains(OAuthClientCreateHandler.INTROSPECTION_PERMISSION))
        assertNull(result.getOrNull())
    }

    @Test
    @DisplayName("A client without introspection needs no permission node")
    fun clientWithoutIntrospectionNeedsNoPermission() {
        val result = OAuthClientCreateHandler.validate(
            "Client",
            "public",
            redirectUri,
            introspectionAllowed = false,
            canGrantIntrospection = false
        )

        assertEquals(listOf(redirectUri), result.getOrNull())
    }
}
