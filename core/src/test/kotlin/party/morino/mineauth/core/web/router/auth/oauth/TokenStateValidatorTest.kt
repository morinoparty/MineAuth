package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.database.DatabaseMigrator
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.repository.AccountRepository
import party.morino.mineauth.core.repository.AccountType
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.ServiceAccountTokenRepository
import party.morino.mineauth.core.repository.TokenType
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals

/**
 * TokenStateValidatorの種別ごとの規則のテスト
 * Bearer認証とイントロスペクションが共有する判定を、実際のDBに対して確認する
 */
class TokenStateValidatorTest {

    @TempDir
    lateinit var tempDir: Path

    private val validator = TokenStateValidator()

    @BeforeEach
    fun setUp() {
        Database.connect(
            url = "jdbc:sqlite:${tempDir.resolve("test.db")}",
            driver = "org.sqlite.JDBC"
        )
        DatabaseMigrator.migrate()
    }

    private suspend fun createClientId(): String {
        val account = AccountRepository.create(AccountType.PLAYER, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
        return OAuthClientRepository.create("Test Client", ClientType.PUBLIC, listOf("https://example.com/cb"), account.accountId)
            .getOrElse { error("Failed to create client: $it") }
            .client.clientId
    }

    private suspend fun createServiceAccountId(): String =
        AccountRepository.create(AccountType.SERVICE, UUID.randomUUID().toString())
            .getOrElse { error("Failed to create account: $it") }
            .accountId

    /** 署名済みトークンをデコードして、検証対象のペイロードを得る */
    private fun payload(kind: TokenKind, configure: JWTCreator.Builder.() -> Unit) =
        JWT.decode(JwtTestSupport.sign(kind, configure = configure))

    @Test
    @DisplayName("A token of a missing client is rejected")
    fun missingClientIsRejected() = runTest {
        val jwt = payload(TokenKind.ACCESS) {
            withJWTId(UUID.randomUUID().toString())
            withClaim("client_id", "deleted-client")
        }

        assertEquals(TokenStateFailure.CLIENT_NOT_FOUND.left(), validator.validate(TokenKind.ACCESS, jwt))
    }

    @Test
    @DisplayName("A revoked jti or a missing jti is rejected")
    fun revokedOrMissingJtiIsRejected() = runTest {
        val clientId = createClientId()
        val tokenId = UUID.randomUUID().toString()
        RevokedTokenRepository.revoke(tokenId, TokenType.ACCESS_TOKEN, clientId, LocalDateTime.now().plusMinutes(5))
            .getOrElse { error("Failed to revoke: $it") }
        val revoked = payload(TokenKind.ACCESS) {
            withJWTId(tokenId)
            withClaim("client_id", clientId)
        }
        val withoutJti = payload(TokenKind.ACCESS) { withClaim("client_id", clientId) }

        assertEquals(TokenStateFailure.REVOKED.left(), validator.validate(TokenKind.ACCESS, revoked))
        assertEquals(TokenStateFailure.REVOKED.left(), validator.validate(TokenKind.ACCESS, withoutJti))
    }

    @Test
    @DisplayName("A service token follows the service token store, not revoked_tokens")
    fun serviceTokenFollowsItsOwnStore() = runTest {
        val accountId = createServiceAccountId()
        val tokenId = UUID.randomUUID().toString()
        ServiceAccountTokenRepository.create(tokenId, accountId, "hash", "console")
            .getOrElse { error("Failed to create token: $it") }
        val jwt = payload(TokenKind.SERVICE) {
            withJWTId(tokenId)
            withClaim("account_id", accountId)
        }

        assertEquals(Unit.right(), validator.validate(TokenKind.SERVICE, jwt))

        // 再発行で旧トークンが失効する
        ServiceAccountTokenRepository.revokeAndCreate(accountId, UUID.randomUUID().toString(), "hash2", "console")
            .getOrElse { error("Failed to reissue: $it") }
        assertEquals(TokenStateFailure.REVOKED.left(), validator.validate(TokenKind.SERVICE, jwt))
    }

    @Test
    @DisplayName("ID and unknown kinds are never valid")
    fun idAndUnknownKindsAreRejected() = runTest {
        val idToken = payload(TokenKind.ID) { withSubject("subject") }

        assertEquals(TokenStateFailure.WRONG_TOKEN_TYPE.left(), validator.validate(TokenKind.ID, idToken))
        assertEquals(TokenStateFailure.WRONG_TOKEN_TYPE.left(), validator.validate(TokenKind.UNKNOWN, idToken))
    }

    @Test
    @DisplayName("Bearer providers cannot accept each other's token kind")
    fun providersRejectEachOthersKind() = runTest {
        val clientId = createClientId()
        val accountId = createServiceAccountId()
        val accessToken = payload(TokenKind.ACCESS) {
            withJWTId(UUID.randomUUID().toString())
            withClaim("client_id", clientId)
        }
        val serviceToken = payload(TokenKind.SERVICE) {
            withJWTId(UUID.randomUUID().toString())
            withClaim("account_id", accountId)
        }

        assertEquals(TokenStateFailure.WRONG_TOKEN_TYPE.left(), validator.validate(TokenKind.SERVICE, accessToken))
        assertEquals(TokenStateFailure.WRONG_TOKEN_TYPE.left(), validator.validate(TokenKind.ACCESS, serviceToken))
    }

}
