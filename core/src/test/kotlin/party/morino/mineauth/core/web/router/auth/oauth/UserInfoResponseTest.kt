package party.morino.mineauth.core.web.router.auth.oauth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.web.components.auth.UserInfoResponse
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * UserInfoResponseのテスト
 * OIDC Core Section 5.3.2 準拠のレスポンス生成をテスト
 */
class UserInfoResponseTest {

    @Nested
    @DisplayName("fromScopes")
    inner class FromScopesTest {

        @Test
        @DisplayName("Returns sub only with openid scope")
        fun returnsSubOnlyWithOpenidScope() {
            // openidスコープのみの場合、subのみが返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid")
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertNull(response.name)
            assertNull(response.nickname)
            assertNull(response.picture)
        }

        @Test
        @DisplayName("Returns all claims with profile scope")
        fun returnsAllClaimsWithProfileScope() {
            // profileスコープが含まれる場合、name, nickname, pictureも返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "profile")
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals("Steve", response.name)
            assertEquals("Steve", response.nickname)
            assertEquals("https://crafthead.net/avatar/550e8400-e29b-41d4-a716-446655440000", response.picture)
        }

        @Test
        @DisplayName("Returns all claims with profile scope only")
        fun returnsAllClaimsWithProfileScopeOnly() {
            // profileスコープのみでもname, nickname, pictureは返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Alex",
                scopes = listOf("profile")
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals("Alex", response.name)
            assertEquals("Alex", response.nickname)
            assertEquals("https://crafthead.net/avatar/550e8400-e29b-41d4-a716-446655440000", response.picture)
        }

        @Test
        @DisplayName("Returns sub only with empty scopes")
        fun returnsSubOnlyWithEmptyScopes() {
            // スコープが空の場合、subのみが返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = emptyList()
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertNull(response.name)
            assertNull(response.nickname)
            assertNull(response.picture)
        }
    }

    @Nested
    @DisplayName("UserInfoResponse")
    inner class UserInfoResponseDirectTest {

        @Test
        @DisplayName("Create response with all fields")
        fun createResponseWithAllFields() {
            val response = UserInfoResponse(
                sub = "uuid-123",
                name = "TestUser",
                nickname = "testuser",
                picture = "https://crafthead.net/avatar/uuid-123"
            )

            assertEquals("uuid-123", response.sub)
            assertEquals("TestUser", response.name)
            assertEquals("testuser", response.nickname)
            assertEquals("https://crafthead.net/avatar/uuid-123", response.picture)
        }

        @Test
        @DisplayName("Create response with sub only")
        fun createResponseWithSubOnly() {
            val response = UserInfoResponse(sub = "uuid-456")

            assertEquals("uuid-456", response.sub)
            assertNull(response.name)
            assertNull(response.nickname)
            assertNull(response.picture)
        }
    }
}
