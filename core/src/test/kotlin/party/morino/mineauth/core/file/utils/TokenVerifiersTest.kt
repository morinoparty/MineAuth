package party.morino.mineauth.core.file.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.JWTVerifier
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.model.TokenKind
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 用途ごとのVerifierが受け付ける種別と有効期限の猶予のテスト
 * 実際に発行したトークンで、種別間の排他性（RFC 8725 Section 3.12）を確認する
 */
class TokenVerifiersTest {

    private val verifiers = JwtTestSupport.verifiers

    // 各種別の新形式トークン（ID Token以外はtoken_typeクレームも持つ）
    private val typedTokens = mapOf(
        TokenKind.ACCESS to JwtTestSupport.sign(TokenKind.ACCESS),
        TokenKind.REFRESH to JwtTestSupport.sign(TokenKind.REFRESH),
        TokenKind.SERVICE to JwtTestSupport.sign(TokenKind.SERVICE),
        TokenKind.ID to JwtTestSupport.sign(TokenKind.ID) { withSubject("subject") }
    )

    // 移行前に発行された旧形式のトークン（typがJWTのまま）
    private val legacyTokens = mapOf(
        TokenKind.ACCESS to JwtTestSupport.signLegacy("token"),
        TokenKind.REFRESH to JwtTestSupport.signLegacy("refresh_token"),
        TokenKind.SERVICE to JwtTestSupport.signLegacy("service_token")
    )

    /** Verifierごとに受け付ける種別の期待値 */
    private val expectations: Map<String, Pair<JWTVerifier, Set<TokenKind>>> = mapOf(
        "bearer" to (verifiers.bearer to setOf(TokenKind.ACCESS, TokenKind.SERVICE)),
        "refreshToken" to (verifiers.refreshToken to setOf(TokenKind.REFRESH)),
        "introspection" to (verifiers.introspection to setOf(TokenKind.ACCESS, TokenKind.REFRESH, TokenKind.SERVICE)),
        "revocation" to (verifiers.revocation to setOf(TokenKind.ACCESS, TokenKind.REFRESH)),
        "idTokenHint" to (verifiers.idTokenHint to setOf(TokenKind.ID))
    )

    private fun accepts(verifier: JWTVerifier, token: String): Boolean = try {
        verifier.verify(token)
        true
    } catch (e: JWTVerificationException) {
        false
    }

    @Test
    @DisplayName("Each verifier accepts its own kinds and rejects every other kind")
    fun verifiersAreMutuallyExclusive() {
        expectations.forEach { (name, expectation) ->
            val (verifier, acceptedKinds) = expectation
            typedTokens.forEach { (kind, token) ->
                assertEquals(kind in acceptedKinds, accepts(verifier, token), "$name verifier with typed $kind")
            }
        }
    }

    @Test
    @DisplayName("Legacy tokens are accepted according to their token_type claim")
    fun legacyTokensFollowTheClaim() {
        expectations.forEach { (name, expectation) ->
            val (verifier, acceptedKinds) = expectation
            legacyTokens.forEach { (kind, token) ->
                assertEquals(kind in acceptedKinds, accepts(verifier, token), "$name verifier with legacy $kind")
            }
        }
    }

    @Test
    @DisplayName("A token whose typ conflicts with its claim is rejected by every verifier")
    fun conflictingTypIsRejected() {
        val conflicting = JwtTestSupport.sign(TokenKind.ACCESS) {
            withClaim(TokenKind.TOKEN_TYPE_CLAIM, "refresh_token")
        }

        expectations.forEach { (name, expectation) ->
            assertTrue(!accepts(expectation.first, conflicting), "$name verifier")
        }
    }

    @Test
    @DisplayName("java-jwt keeps the explicit typ of every kind")
    fun issuedTokensKeepTheirTyp() {
        assertEquals("at+jwt", JWT.decode(typedTokens.getValue(TokenKind.ACCESS)).type)
        assertEquals("rt+jwt", JWT.decode(typedTokens.getValue(TokenKind.REFRESH)).type)
        assertEquals("service+jwt", JWT.decode(typedTokens.getValue(TokenKind.SERVICE)).type)

        val idToken = JWT.decode(typedTokens.getValue(TokenKind.ID))
        assertEquals("JWT", idToken.type)
        assertTrue(idToken.getClaim(TokenKind.TOKEN_TYPE_CLAIM).isMissing)
    }

    @Test
    @DisplayName("Revocation accepts a token expired within 30 days and rejects one beyond")
    fun revocationHasBoundedExpiryLeeway() {
        val withinBound = JwtTestSupport.sign(TokenKind.REFRESH, expiresIn = Duration.ofDays(-29))
        val beyondBound = JwtTestSupport.sign(TokenKind.REFRESH, expiresIn = Duration.ofDays(-31))

        assertTrue(accepts(verifiers.revocation, withinBound))
        assertFailsWith<TokenExpiredException> { verifiers.revocation.verify(beyondBound) }
    }

    @Test
    @DisplayName("id_token_hint accepts a token expired within 30 days and rejects one beyond")
    fun idTokenHintHasBoundedExpiryLeeway() {
        val withinBound = JwtTestSupport.sign(TokenKind.ID, expiresIn = Duration.ofDays(-29)) { withSubject("subject") }
        val beyondBound = JwtTestSupport.sign(TokenKind.ID, expiresIn = Duration.ofDays(-31)) { withSubject("subject") }

        assertTrue(accepts(verifiers.idTokenHint, withinBound))
        assertFailsWith<TokenExpiredException> { verifiers.idTokenHint.verify(beyondBound) }
    }

    @Test
    @DisplayName("The refresh token verifier has no leeway")
    fun refreshTokenVerifierIsStrict() {
        val justExpired = JwtTestSupport.sign(TokenKind.REFRESH, expiresIn = Duration.ofSeconds(-1))

        assertFailsWith<TokenExpiredException> { verifiers.refreshToken.verify(justExpired) }
    }

    @Test
    @DisplayName("Bearer and introspection accept the same clock skew and reject tokens beyond it")
    fun bearerAndIntrospectionShareTheLeeway() {
        // 有効期限は秒単位に切り捨てられるため、遅い環境でも猶予（3秒）に収まるよう1秒前にする
        val withinLeeway = JwtTestSupport.sign(TokenKind.ACCESS, expiresIn = Duration.ofSeconds(-1))
        val beyondLeeway = JwtTestSupport.sign(TokenKind.ACCESS, expiresIn = Duration.ofSeconds(-10))

        assertTrue(accepts(verifiers.bearer, withinLeeway))
        assertTrue(accepts(verifiers.introspection, withinLeeway))
        assertFailsWith<TokenExpiredException> { verifiers.bearer.verify(beyondLeeway) }
        assertFailsWith<TokenExpiredException> { verifiers.introspection.verify(beyondLeeway) }
    }

    @Test
    @DisplayName("A token signed with another key is rejected even within the expiry leeway")
    fun forgedSignatureIsRejected() {
        val forged = JwtTestSupport.sign(TokenKind.REFRESH, algorithm = JwtTestSupport.newAlgorithm())

        assertFailsWith<JWTVerificationException> { verifiers.revocation.verify(forged) }
    }

    @Test
    @DisplayName("Unknown kind cannot be issued")
    fun unknownKindCannotBeIssued() {
        assertFailsWith<IllegalArgumentException> { JwtProvider.newToken(TokenKind.UNKNOWN) }
    }
}
