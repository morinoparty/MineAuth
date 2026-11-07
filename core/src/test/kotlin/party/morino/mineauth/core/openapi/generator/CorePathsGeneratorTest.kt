package party.morino.mineauth.core.openapi.generator

import kotlinx.serialization.descriptors.elementNames
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.openapi.model.Operation
import party.morino.mineauth.core.openapi.model.Schema
import party.morino.mineauth.core.web.router.auth.oauth.IntrospectionResponse

/**
 * CorePathsGeneratorのテスト
 */
class CorePathsGeneratorTest {

    private val paths = CorePathsGenerator().generate()

    /**
     * フォーム形式のリクエストボディのスキーマを取り出す
     */
    private fun Operation.formSchema(): Schema =
        requestBody?.content?.get("application/x-www-form-urlencoded")?.schema
            ?: error("$operationId has no form body")

    @Test
    @DisplayName("UserInfo is documented for GET and POST with distinct operation IDs")
    fun userInfoIsDocumentedForGetAndPost() {
        val userInfo = paths.getValue("/oauth2/userinfo")

        assertEquals("get_oauth2_userinfo", userInfo.get?.operationId)
        assertEquals("post_oauth2_userinfo", userInfo.post?.operationId)
    }

    @Test
    @DisplayName("Token documents the rotation failure and requires only grant_type in the body")
    fun tokenDocumentsServerErrorAndRequiresOnlyGrantType() {
        val token = paths.getValue("/oauth2/token").post!!

        assertEquals(setOf("200", "400", "401", "429", "500", "503"), token.responses.keys)
        // client_secret_basicではクレデンシャルを本文に含めないが、client_secret_post用の項目は記載する
        assertEquals(listOf("grant_type"), token.formSchema().required)
        assertTrue(token.formSchema().properties.orEmpty().keys.containsAll(setOf("client_id", "client_secret")))
    }

    @Test
    @DisplayName("Revoke documents the server error and requires only token in the body")
    fun revokeDocumentsServerErrorAndRequiresOnlyToken() {
        val revoke = paths.getValue("/oauth2/revoke").post!!

        assertEquals(setOf("200", "400", "401", "429", "500", "503"), revoke.responses.keys)
        // client_secret_basicではクレデンシャルを本文に含めない
        assertEquals(listOf("token"), revoke.formSchema().required)
    }

    @Test
    @DisplayName("Introspection is documented with the permission rejection, the server error and every response field")
    fun introspectionIsDocumented() {
        val introspect = paths.getValue("/oauth2/introspect").post!!

        assertEquals("post_oauth2_introspect", introspect.operationId)
        assertEquals(setOf("200", "400", "401", "403", "429", "500", "503"), introspect.responses.keys)
        assertEquals(listOf("token"), introspect.formSchema().required)

        // 実際のレスポンスの型と同じフィールドを記載していることを確認する
        val responseSchema = introspect.responses.getValue("200").content?.get("application/json")?.schema
        val documentedFields = responseSchema?.properties?.keys
        assertEquals(IntrospectionResponse.serializer().descriptor.elementNames.toSet(), documentedFields)
    }

    @Test
    @DisplayName("End session is documented for GET and POST with the same parameters")
    fun endSessionIsDocumentedForGetAndPost() {
        val endSession = paths.getValue("/oauth2/end_session")
        val get = endSession.get!!
        val post = endSession.post!!

        assertNotEquals(get.operationId, post.operationId)
        assertEquals(get.responses, post.responses)
        assertEquals(setOf("200", "302", "400"), get.responses.keys)

        // GETはクエリ、POSTはフォームで同じパラメータを受け取る
        val queryParameters = get.parameters.orEmpty()
        assertTrue(queryParameters.all { it.location == "query" })
        assertEquals(post.formSchema().properties?.keys, queryParameters.map { it.name }.toSet())
    }
}
