package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.database.RevokedTokens
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.model.grantId
import party.morino.mineauth.core.model.tokenKind
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.RevokedGrantRepository
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * グラント単位の失効のテスト（Issue #469）
 * 実際のルートとSQLiteを通して、リプレイ・再利用・失効が同じグラントのトークンに波及することを確認する
 */
class GrantRevocationTest : OAuthRouteTestBase() {

    private val redirectUri = "https://rp.example.com/callback"

    // RFC 7636 Section 4.1 を満たす固定のcode_verifier
    private val codeVerifier = "a".repeat(43)

    // レスポンスキャッシュの現在時刻（grace期間の経過を制御する）
    // ローテーションが書く失効日時もこの時計で記録されるため、/oauth2/revokeが書く実時刻と並ぶよう実時刻から始める
    private var cacheNow = System.currentTimeMillis()

    private val graceWindowEnd = RefreshTokenResponseCache.DEFAULT_GRACE_PERIOD_MILLIS + 1

    private val validator = TokenStateValidator()
    private val logs = RecordingLogHandler()

    /** 認可コードの交換とローテーションで受け取るトークンの組 */
    private data class Tokens(val access: String, val refresh: String)

    @BeforeEach
    fun setUpSigningKeyAndClock() {
        // 発行に使う鍵をKeyUtilsのキャッシュではなくテスト用の鍵に差し替える
        mockkObject(JwtProvider)
        every { JwtProvider.algorithm } returns JwtTestSupport.algorithm

        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns logs.newLogger()
        loadKoinModules(
            module {
                single<MineAuth> { plugin }
                single { RefreshTokenResponseCache(clock = { cacheNow }) }
            }
        )
    }

    @AfterEach
    fun restoreSigningKey() {
        unmockkObject(JwtProvider)
        OAuthRouter.authorizedData.clear()
    }

    private fun codeChallenge(): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash)
    }

    /** 認可コードの発行時にAuthorizeRouterが保存する内容を再現する */
    private fun issueCode(clientId: String, grantId: String): String {
        val code = UUID.randomUUID().toString()
        OAuthRouter.authorizedData[code] = AuthorizedData(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = "openid",
            state = "state",
            codeChallenge = codeChallenge(),
            uniqueId = UUID.randomUUID(),
            grantId = grantId
        )
        return code
    }

    /** grant_idの導入前に発行された形式のリフレッシュトークンを発行する */
    private fun legacyRefreshToken(clientId: String): String = JwtTestSupport.sign(TokenKind.REFRESH) {
        withJWTId(UUID.randomUUID().toString())
        withClaim("client_id", clientId)
        withClaim("playerUniqueId", UUID.randomUUID().toString())
        withClaim("scope", "openid")
        withClaim("state", "state")
    }

    private suspend fun ApplicationTestBuilder.redeem(
        code: String,
        owner: OAuthClientCreationResult,
        verifier: String = codeVerifier
    ) = client.submitForm(
        url = "/oauth2/token",
        formParameters = parameters {
            append("grant_type", "authorization_code")
            append("code", code)
            append("redirect_uri", redirectUri)
            append("code_verifier", verifier)
            append("client_id", owner.client.clientId)
            append("client_secret", owner.clientSecret!!)
        }
    )

    private suspend fun ApplicationTestBuilder.refresh(refreshToken: String, owner: OAuthClientCreationResult) =
        client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("client_id", owner.client.clientId)
                append("client_secret", owner.clientSecret!!)
            }
        )

    private suspend fun ApplicationTestBuilder.revoke(token: String, owner: OAuthClientCreationResult) =
        client.submitForm(
            url = "/oauth2/revoke",
            formParameters = parameters {
                append("token", token)
                append("client_id", owner.client.clientId)
                append("client_secret", owner.clientSecret!!)
            }
        )

    private suspend fun HttpResponse.tokens(): Tokens {
        assertEquals(HttpStatusCode.OK, status)
        val body = jsonBody()
        return Tokens(
            access = body.getValue("access_token").jsonPrimitive.content,
            refresh = body.getValue("refresh_token").jsonPrimitive.content
        )
    }

    private suspend fun assertInvalidGrant(response: HttpResponse) {
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals("invalid_grant", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    private suspend fun assertServerError(response: HttpResponse) {
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("server_error", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    /**
     * リフレッシュトークンの失効記録をデータベースに直接書く
     * 別のサーバーや以前のバージョンが書いた記録、タイムゾーン変換でずれたrevoked_atを再現するために使う
     *
     * @param refreshToken 記録するリフレッシュトークン
     * @param clientId トークンを発行したクライアントID
     * @param revokedAt revoked_atに書く日時
     * @param revokedAtEpochMillis revoked_at_msに書く時刻（nullなら記録しない）
     */
    private fun writeRevocationRecord(
        refreshToken: String,
        clientId: String,
        revokedAt: LocalDateTime,
        revokedAtEpochMillis: Long?
    ) {
        val jwt = JWT.decode(refreshToken)
        transaction {
            RevokedTokens.insert {
                it[tokenId] = jwt.id
                it[tokenType] = TokenType.REFRESH_TOKEN.value
                it[RevokedTokens.clientId] = clientId
                it[RevokedTokens.revokedAt] = revokedAt
                it[RevokedTokens.revokedAtEpochMillis] = revokedAtEpochMillis
                it[expiresAt] = LocalDateTime.ofInstant(jwt.expiresAt.toInstant(), ZoneId.systemDefault())
            }
        }
    }

    /** Bearer認証とイントロスペクションが共有する判定でアクセストークンの状態を確認する */
    private suspend fun assertAccessTokenValid(accessToken: String) =
        assertEquals(Unit.right(), validator.validate(TokenKind.ACCESS, JWT.decode(accessToken)))

    private suspend fun assertAccessTokenGrantRevoked(accessToken: String) = assertEquals(
        TokenStateFailure.GRANT_REVOKED.left(),
        validator.validate(TokenKind.ACCESS, JWT.decode(accessToken))
    )

    @Test
    @DisplayName("Replaying a code stops the tokens of the first redemption")
    fun codeReplayRevokesTheGrant() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val code = issueCode(owner.client.clientId, grantId)
        val tokens = redeem(code, owner).tokens()
        assertAccessTokenValid(tokens.access)

        val replay = redeem(code, owner)
        val unknown = redeem("unknown-code", owner)

        // リプレイと未知のコードは応答から区別できない
        assertInvalidGrant(replay)
        assertEquals(unknown.bodyAsText(), replay.bodyAsText())
        assertAccessTokenGrantRevoked(tokens.access)
        assertInvalidGrant(refresh(tokens.refresh, owner))

        // ログには調査に必要な識別子だけを残し、認可コードは出力しない
        // （OAuthエラーの共通ログも同じロガーに届く場合があるため、リプレイの記録に絞って確認する）
        val warning = logs.warnings.single { it.contains("replay") }
        assertContains(warning, grantId)
        assertContains(warning, owner.client.clientId)
        assertFalse(logs.warnings.any { it.contains(code) })
    }

    @Test
    @DisplayName("A replay by another client is denied without revoking the grant")
    fun replayByAnotherClientDoesNotRevoke() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val other = registerClient(ClientType.CONFIDENTIAL)
        val code = issueCode(owner.client.clientId, UUID.randomUUID().toString())
        val tokens = redeem(code, owner).tokens()

        val replay = redeem(code, other)
        val unknown = redeem("unknown-code", other)

        assertInvalidGrant(replay)
        assertEquals(unknown.bodyAsText(), replay.bodyAsText())
        assertAccessTokenValid(tokens.access)
        assertEquals(HttpStatusCode.OK, refresh(tokens.refresh, owner).status)
    }

    @Test
    @DisplayName("Reuse after the grace window revokes the grant")
    fun reuseAfterGraceWindowRevokesTheGrant() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
        val rotated = refresh(first.refresh, owner).tokens()

        cacheNow += graceWindowEnd
        assertInvalidGrant(refresh(first.refresh, owner))

        // 再利用の時点で最新だったトークンも失効する
        assertInvalidGrant(refresh(rotated.refresh, owner))
        assertAccessTokenGrantRevoked(rotated.access)

        // （OAuthエラーの共通ログも同じロガーに届く場合があるため、再利用の記録に絞って確認する）
        val warning = logs.warnings.single { it.contains("reuse") }
        assertContains(warning, grantId)
        assertContains(warning, owner.client.clientId)
        assertFalse(logs.warnings.any { it.contains(first.refresh) })
    }

    @Test
    @DisplayName("A failed read of the token state answers server_error and revokes nothing")
    fun failedReadIsNotTreatedAsReuse() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val tokens = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
        // jtiの読み取りを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_tokens") }

        // invalid_grantはトークンが無効だという表明になるので、読み取りの失敗では返さない
        assertServerError(refresh(tokens.refresh, owner))

        assertFalse(RevokedGrantRepository.isRevoked(grantId))
        assertContains(logs.severes.single(), "refresh token")
    }

    @Test
    @DisplayName("The grace decision uses revoked_at_ms even when revoked_at is hours off")
    fun graceDecisionUsesTheEpochMillis() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
        // 別のサーバーが1秒前にローテーションした記録を再現する（このサーバーのキャッシュは応答を持たない）
        // revoked_atは、夏時間の切り替わりやタイムゾーン変換でずれた値を再現する
        // ローカル時刻とUTCのどちらとして読んでもgrace期間から数時間以上外れるよう、1日先にする
        writeRevocationRecord(
            first.refresh,
            owner.client.clientId,
            revokedAt = LocalDateTime.now().plusDays(1),
            revokedAtEpochMillis = cacheNow - 1_000
        )

        assertInvalidGrant(refresh(first.refresh, owner))

        // revoked_at_msでは1秒前のローテーションなので、grace期間内の再試行として拒否だけしてグラントは残す
        assertFalse(RevokedGrantRepository.isRevoked(grantId))
        assertAccessTokenValid(first.access)
        assertContains(logs.warnings.single { it.contains("within the grace period") }, grantId)
    }

    @Test
    @DisplayName("A spent token recorded without revoked_at_ms is treated as reuse")
    fun recordWithoutEpochMillisIsTreatedAsReuse() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
        // revoked_at_msの追加前や、追加前のバージョンが書いた記録を再現する。revoked_atは直前の時刻にしておく
        writeRevocationRecord(
            first.refresh,
            owner.client.clientId,
            revokedAt = LocalDateTime.now(),
            revokedAtEpochMillis = null
        )

        assertInvalidGrant(refresh(first.refresh, owner))

        // 直前のローテーションだと示せないので、grace期間外の再利用としてグラント全体を失効させる
        assertAccessTokenGrantRevoked(first.access)
        assertContains(logs.warnings.single { it.contains("reuse") }, grantId)
    }

    @Test
    @DisplayName("Revoking a refresh token rejects the access token of the same grant")
    fun refreshRevocationReachesTheAccessToken() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val tokens = redeem(issueCode(owner.client.clientId, UUID.randomUUID().toString()), owner).tokens()

        assertEquals(HttpStatusCode.OK, revoke(tokens.refresh, owner).status)

        // この判定はBearer認証とイントロスペクションが共有している
        assertAccessTokenGrantRevoked(tokens.access)
    }

    @Test
    @DisplayName("A legacy token rotates into a new grant kept by later rotations")
    fun legacyTokenRotatesIntoNewGrant() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)

        val rotated = refresh(legacyRefreshToken(owner.client.clientId), owner).tokens()
        val grantId = assertNotNull(JWT.decode(rotated.refresh).grantId())
        val next = refresh(rotated.refresh, owner).tokens()

        assertEquals(grantId, JWT.decode(rotated.access).grantId())
        assertEquals(grantId, JWT.decode(next.refresh).grantId())
        assertEquals(grantId, JWT.decode(next.access).grantId())
    }

    private suspend fun HttpResponse.tokenField(name: String): String = jsonBody().getValue(name).jsonPrimitive.content

    @Test
    @DisplayName("Authorization code grant issues tokens accepted by the verifier of their own kind")
    fun authorizationCodeGrantIssuesTypedTokens() = oauthTestApplication {
        val clientId = registerClient(ClientType.PUBLIC).client.clientId
        val playerId = UUID.randomUUID()
        val grantId = UUID.randomUUID().toString()
        val code = UUID.randomUUID().toString()
        OAuthRouter.authorizedData[code] = AuthorizedData(
            clientId = clientId,
            redirectUri = redirectUri,
            scope = "openid",
            state = "state",
            codeChallenge = codeChallenge(),
            uniqueId = playerId,
            grantId = grantId
        )

        val response = client.submitForm(
            url = "/oauth2/token",
            formParameters = parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", redirectUri)
                append("code_verifier", codeVerifier)
                append("client_id", clientId)
            }
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val verifiers = JwtTestSupport.verifiers
        val accessToken = verifiers.bearer.verify(response.tokenField("access_token"))
        val refreshToken = verifiers.refreshToken.verify(response.tokenField("refresh_token"))
        val idToken = verifiers.idTokenHint.verify(response.tokenField("id_token"))
        assertEquals(TokenKind.ACCESS, accessToken.tokenKind())
        assertEquals(TokenKind.REFRESH, refreshToken.tokenKind())
        assertEquals(TokenKind.ID, idToken.tokenKind())

        // RFC 9068 Section 2.2: アクセストークンのsubはプレイヤーのUUID
        assertEquals(playerId.toString(), accessToken.subject)

        // grant_idはアクセストークンとリフレッシュトークンだけが持つ
        assertEquals(grantId, accessToken.grantId())
        assertEquals(grantId, refreshToken.grantId())
        assertNull(idToken.grantId())
    }
}
