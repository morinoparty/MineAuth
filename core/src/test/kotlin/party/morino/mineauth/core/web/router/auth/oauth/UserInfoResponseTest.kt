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
            // profileスコープが含まれる場合、name, nickname, picture, preferred_usernameも返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "profile")
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals("Steve", response.name)
            assertEquals("Steve", response.nickname)
            assertEquals("https://crafthead.net/avatar/550e8400-e29b-41d4-a716-446655440000", response.picture)
            assertEquals("Steve", response.preferredUsername)
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

        @Test
        @DisplayName("Returns email claims with email scope and email provided")
        fun returnsEmailClaimsWithEmailScope() {
            // emailスコープが含まれ、emailが提供されている場合、email, email_verifiedも返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "email"),
                email = "550e8400-e29b-41d4-a716-446655440000-Steve@example.com"
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals("550e8400-e29b-41d4-a716-446655440000-Steve@example.com", response.email)
            assertEquals(false, response.emailVerified)
            assertNull(response.name)
        }

        @Test
        @DisplayName("Returns no email claims when email scope but no email provided")
        fun returnsNoEmailWhenNoEmailProvided() {
            // emailスコープが含まれるが、emailが提供されていない場合、emailクレームは返されない
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "email"),
                email = null
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertNull(response.email)
            assertNull(response.emailVerified)
        }

        @Test
        @DisplayName("Returns all claims with profile and email scopes")
        fun returnsAllClaimsWithProfileAndEmailScopes() {
            // profile + emailスコープの場合、全てのクレームが返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "profile", "email"),
                email = "steve@example.com"
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals("Steve", response.name)
            assertEquals("Steve", response.nickname)
            assertEquals("https://crafthead.net/avatar/550e8400-e29b-41d4-a716-446655440000", response.picture)
            assertEquals("steve@example.com", response.email)
            assertEquals(false, response.emailVerified)
        }

        @Test
        @DisplayName("Returns roles claims with roles scope and roles provided")
        fun returnsRolesClaimsWithRolesScope() {
            // rolesスコープが含まれ、rolesが提供されている場合、rolesが返される
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "roles"),
                roles = listOf("admin", "vip", "builder")
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertEquals(listOf("admin", "vip", "builder"), response.roles)
            assertNull(response.name)
        }

        @Test
        @DisplayName("Returns no roles claims when roles scope but no roles provided")
        fun returnsNoRolesWhenNoRolesProvided() {
            // rolesスコープが含まれるが、rolesが提供されていない場合、rolesクレームは返されない
            val response = UserInfoResponse.fromScopes(
                sub = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve",
                scopes = listOf("openid", "roles"),
                roles = null
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000", response.sub)
            assertNull(response.roles)
        }
    }

    @Nested
    @DisplayName("generateEmail")
    inner class GenerateEmailTest {

        @Test
        @DisplayName("Generates email with uuid and username placeholders")
        fun generatesEmailWithPlaceholders() {
            // <uuid>と<username>のプレースホルダーを置換
            val email = UserInfoResponse.generateEmail(
                emailFormat = "<uuid>-<username>@example.com",
                uuid = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve"
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000-Steve@example.com", email)
        }

        @Test
        @DisplayName("Generates email with only uuid placeholder")
        fun generatesEmailWithOnlyUuidPlaceholder() {
            // <uuid>のみのプレースホルダー
            val email = UserInfoResponse.generateEmail(
                emailFormat = "<uuid>@minecraft.example.com",
                uuid = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve"
            )

            assertEquals("550e8400-e29b-41d4-a716-446655440000@minecraft.example.com", email)
        }

        @Test
        @DisplayName("Generates email with only username placeholder")
        fun generatesEmailWithOnlyUsernamePlaceholder() {
            // <username>のみのプレースホルダー
            val email = UserInfoResponse.generateEmail(
                emailFormat = "<username>@minecraft.example.com",
                uuid = "550e8400-e29b-41d4-a716-446655440000",
                username = "Steve"
            )

            assertEquals("Steve@minecraft.example.com", email)
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
