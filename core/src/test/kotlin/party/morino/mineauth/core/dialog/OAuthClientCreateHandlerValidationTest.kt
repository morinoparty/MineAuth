package party.morino.mineauth.core.dialog

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * クライアント作成ダイアログの入力値検証のテスト
 * トークンイントロスペクションの許可が、権限を持つ実行者とConfidentialクライアントの組み合わせでのみ受け付けられることを確認する（Issue #480）
 */
class OAuthClientCreateHandlerValidationTest {

    private val redirectUri = "https://example.com/callback"

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
}
