package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.getOrElse
import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.ApplicationTestBuilder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
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
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientCreationResult
import party.morino.mineauth.core.repository.RevokedGrantError
import party.morino.mineauth.core.repository.RevokedGrantRepository
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenRevocationTime
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Base64
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

    /** ローテーションの失効の書き込みを、状態の読み取りと署名を終えた時点で1回だけ止める */
    private class RotationWriteGate {
        // 書き込みの直前に到達したことを知らせる
        val held = CompletableDeferred<Unit>()

        // 完了させると書き込みを続ける
        val release = CompletableDeferred<Unit>()
        val armed = AtomicBoolean(true)
    }

    /**
     * 最初のrevokeIfAbsentを止めるゲートを設定してテストを実行する
     * MockKの解除はスレッドローカルで管理されるため、コルーチンの外（同じスレッド）で設定と解除を行う
     */
    private fun withRotationWriteGate(block: (RotationWriteGate) -> Unit) {
        val gate = RotationWriteGate()
        mockkObject(RevokedTokenRepository)
        try {
            coEvery { RevokedTokenRepository.revokeIfAbsent(any(), any(), any(), any(), any()) } coAnswers {
                if (gate.armed.getAndSet(false)) {
                    gate.held.complete(Unit)
                    gate.release.await()
                }
                callOriginal()
            }
            block(gate)
        } finally {
            unmockkObject(RevokedTokenRepository)
        }
    }

    /**
     * JVMの既定のタイムゾーンを差し替えてテストを実行し、終わったら必ず元に戻す
     * grace期間の判定がサーバーのタイムゾーンに左右されないことを、UTC以外のタイムゾーンで確かめるために使う
     */
    private fun withDefaultTimeZone(zoneId: String, block: () -> Unit) {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        try {
            block()
        } finally {
            TimeZone.setDefault(previous)
        }
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
    @DisplayName("A code spent by a failed attempt counts as redeemed")
    fun failedAttemptSpendsTheCode() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val code = issueCode(owner.client.clientId, grantId)

        assertInvalidGrant(redeem(code, owner, verifier = "b".repeat(43)))
        assertInvalidGrant(redeem(code, owner))

        assertTrue(RevokedGrantRepository.isRevoked(grantId))
    }

    @Test
    @DisplayName("A failed grant write still denies the replayed code")
    fun failedGrantWriteDeniesTheReplay() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val code = issueCode(owner.client.clientId, UUID.randomUUID().toString())
        redeem(code, owner).tokens()
        // グラントの失効の書き込みを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_grants") }

        assertInvalidGrant(redeem(code, owner))

        assertContains(logs.severes.single(), "authorization code replay")
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
    @DisplayName("Reuse within the grace window returns the same response and revokes nothing")
    fun reuseWithinGraceWindowRevokesNothing() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()

        val rotated = refresh(first.refresh, owner)
        cacheNow += graceWindowEnd - 2
        val replayed = refresh(first.refresh, owner)

        assertEquals(rotated.bodyAsText(), replayed.bodyAsText())
        assertFalse(RevokedGrantRepository.isRevoked(grantId))
        assertAccessTokenValid(rotated.tokens().access)
    }

    @Test
    @DisplayName("Concurrent refreshes share one response and revoke nothing")
    fun concurrentRefreshesRevokeNothing() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()

        val responses = coroutineScope {
            (1..4).map { async { refresh(first.refresh, owner) } }.awaitAll()
        }

        assertEquals(1, responses.map { it.bodyAsText() }.toSet().size)
        assertAccessTokenValid(responses.first().tokens().access)
        assertFalse(RevokedGrantRepository.isRevoked(grantId))
    }

    @Test
    @DisplayName("A failed grant write still denies the reused token")
    fun failedGrantWriteDeniesTheReuse() {
        // MockKの解除はスレッドローカルで管理されるため、コルーチンの外（同じスレッド）で設定と解除を行う
        mockkObject(RevokedGrantRepository)
        try {
            coEvery { RevokedGrantRepository.revokeForReusedToken(any(), any(), any()) } returns
                RevokedGrantError.DatabaseError("database is down").left()

            oauthTestApplication {
                val owner = registerClient(ClientType.CONFIDENTIAL)
                val first = redeem(issueCode(owner.client.clientId, UUID.randomUUID().toString()), owner).tokens()
                refresh(first.refresh, owner).tokens()

                cacheNow += graceWindowEnd
                assertInvalidGrant(refresh(first.refresh, owner))

                assertContains(logs.severes.single(), "refresh token reuse")
            }
        } finally {
            unmockkObject(RevokedGrantRepository)
        }
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
    @DisplayName("A failed read of the grant state answers server_error and spends nothing")
    fun failedGrantReadIsNotTreatedAsRevoked() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val tokens = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
        // グラントの読み取りを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_grants") }

        assertServerError(refresh(tokens.refresh, owner))

        // ローテーションに進んでいないので、提示したトークンは使用済みになっていない
        assertEquals(
            TokenRevocationTime.NotRevoked.right(),
            RevokedTokenRepository.findRevokedAt(JWT.decode(tokens.refresh).id)
        )
        val severe = logs.severes.single()
        assertContains(severe, grantId)
        assertFalse(severe.contains(tokens.refresh))
    }

    @Test
    @DisplayName("A presentation with no cached response revokes the grant only after the grace window")
    fun presentationWithoutCachedResponseFollowsTheGraceWindow() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
        val rotated = refresh(first.refresh, owner).tokens()
        // 再起動の後や別のサーバーへの再試行を再現するため、応答を持たないキャッシュに差し替える（時計は共有する）
        loadKoinModules(module { single { RefreshTokenResponseCache(clock = { cacheNow }) } })

        // grace期間内: 応答を失ったクライアントの再試行でもありうるので、拒否だけしてグラントは残す
        cacheNow += 1_000
        assertInvalidGrant(refresh(first.refresh, owner))
        assertFalse(RevokedGrantRepository.isRevoked(grantId))
        assertAccessTokenValid(rotated.access)
        assertContains(logs.warnings.single { it.contains("within the grace period") }, grantId)

        // grace期間後: 再利用としてグラント全体を失効させる
        cacheNow += RefreshTokenResponseCache.DEFAULT_GRACE_PERIOD_MILLIS
        assertInvalidGrant(refresh(first.refresh, owner))
        assertAccessTokenGrantRevoked(rotated.access)
    }

    @Test
    @DisplayName("A retry on a server in another time zone stays within the grace window")
    fun retryInAnotherTimeZoneStaysWithinGraceWindow() = withDefaultTimeZone("America/New_York") {
        oauthTestApplication {
            val owner = registerClient(ClientType.CONFIDENTIAL)
            val grantId = UUID.randomUUID().toString()
            val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
            val rotated = refresh(first.refresh, owner).tokens()
            // 同じデータベースを共有する、タイムゾーンの異なる別のサーバーへの再試行を再現する（時計は共有する）
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            loadKoinModules(module { single { RefreshTokenResponseCache(clock = { cacheNow }) } })

            // revoked_atはローテーションしたサーバー（ニューヨーク）のローカル時刻で残る
            // 東京のサーバーがこれを自分のローカル時刻として読むと半日以上ずれるが、判定はrevoked_at_msで行う
            val record = transaction {
                RevokedTokens.selectAll().where { RevokedTokens.tokenId eq JWT.decode(first.refresh).id }.single()
            }
            val revokedAtMillis = assertNotNull(record[RevokedTokens.revokedAtEpochMillis])
            assertEquals(
                LocalDateTime.ofInstant(Instant.ofEpochMilli(revokedAtMillis), ZoneId.of("America/New_York")),
                record[RevokedTokens.revokedAt]
            )

            cacheNow += 5_000
            assertInvalidGrant(refresh(first.refresh, owner))

            // ローテーションの数秒後の再試行なので、グラントは失効させない
            assertFalse(RevokedGrantRepository.isRevoked(grantId))
            assertAccessTokenValid(rotated.access)
        }
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
    @DisplayName("A revocation time far in the future does not suspend reuse detection")
    fun futureRevocationTimeStillRevokesTheGrant() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val first = redeem(issueCode(owner.client.clientId, UUID.randomUUID().toString()), owner).tokens()
        val rotated = refresh(first.refresh, owner).tokens()
        // 読み取るサーバーの時計から見て、失効日時が数時間先にある状態を再現する（応答を持たないキャッシュに差し替える）
        loadKoinModules(module { single { RefreshTokenResponseCache(clock = { cacheNow }) } })
        cacheNow -= Duration.ofHours(3).toMillis()

        assertInvalidGrant(refresh(first.refresh, owner))

        // 時計のずれでは説明できない時刻なので、grace期間外の再利用としてグラント全体を失効させる
        assertAccessTokenGrantRevoked(rotated.access)
    }

    @Test
    @DisplayName("A failed grant read does not fail the rotation of a legacy token")
    fun failedGrantReadDoesNotFailLegacyRotation() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val legacy = legacyRefreshToken(owner.client.clientId)
        // グラントの読み取りを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_grants") }

        // 旧トークンは使用済みになるが、クライアントは後継のトークンを受け取る
        val rotated = refresh(legacy, owner).tokens()
        assertNotNull(JWT.decode(rotated.refresh).grantId())
        val legacyRevocation = RevokedTokenRepository.findRevokedAt(JWT.decode(legacy).id).getOrNull()
        assertTrue(legacyRevocation is TokenRevocationTime.Recorded)

        // キャッシュから返す再提示は、新トークンのグラントを確認できないのでserver_errorになる
        assertServerError(refresh(legacy, owner))
    }

    @Test
    @DisplayName("A failure while signing leaves the refresh token usable for a retry")
    fun signingFailureLeavesTheTokenUnspent() {
        val failNextSigning = AtomicBoolean(false)
        // MockKの設定はコルーチンの外（同じスレッド）で行う
        every { JwtProvider.algorithm } answers {
            if (failNextSigning.getAndSet(false)) throw IllegalStateException("signing key unavailable")
            JwtTestSupport.algorithm
        }

        oauthTestApplication {
            val owner = registerClient(ClientType.CONFIDENTIAL)
            val grantId = UUID.randomUUID().toString()
            val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()

            failNextSigning.set(true)
            val failed = runCatching { refresh(first.refresh, owner) }
            // 想定外の例外は捕捉せずに送出する（テストエンジンは呼び出し側へ再送出し、本番のサーバーは500で応答する）
            assertFalse(failed.getOrNull()?.status == HttpStatusCode.OK)

            val retried = refresh(first.refresh, owner).tokens()
            assertAccessTokenValid(retried.access)
            assertFalse(RevokedGrantRepository.isRevoked(grantId))
        }
    }

    @Test
    @DisplayName("A legacy token revoked during its rotation yields no new tokens")
    fun legacyTokenRevokedDuringRotation() = withRotationWriteGate { gate ->
        oauthTestApplication {
            val owner = registerClient(ClientType.CONFIDENTIAL)
            val legacy = legacyRefreshToken(owner.client.clientId)

            val rotation = coroutineScope {
                val pending = async { refresh(legacy, owner) }
                // 状態の読み取りと署名を終えたローテーションを止めている間に、/oauth2/revokeで失効させる
                gate.held.await()
                assertEquals(HttpStatusCode.OK, revoke(legacy, owner).status)
                gate.release.complete(Unit)
                pending.await()
            }

            assertInvalidGrant(rotation)
            assertInvalidGrant(refresh(legacy, owner))
        }
    }

    @Test
    @DisplayName("A token spent by another server during the rotation does not fork the grant")
    fun rotationLosingToAnotherServerDoesNotFork() = withRotationWriteGate { gate ->
        oauthTestApplication {
            val owner = registerClient(ClientType.CONFIDENTIAL)
            val grantId = UUID.randomUUID().toString()
            val first = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()
            val decoded = JWT.decode(first.refresh)

            val rotation = coroutineScope {
                val pending = async { refresh(first.refresh, owner) }
                gate.held.await()
                // 同じデータベースを共有する別のサーバーが、先にローテーションをコミットしたことを再現する
                RevokedTokenRepository.revoke(
                    decoded.id, TokenType.REFRESH_TOKEN, owner.client.clientId,
                    LocalDateTime.ofInstant(decoded.expiresAt.toInstant(), ZoneId.systemDefault())
                ).getOrElse { error("Failed to revoke: $it") }
                gate.release.complete(Unit)
                pending.await()
            }

            // 2本目のチェーンは渡さない。並行したローテーションは再利用ではないので、相手のチェーンは残す
            assertInvalidGrant(rotation)
            assertFalse(RevokedGrantRepository.isRevoked(grantId))
            assertAccessTokenValid(first.access)
            assertContains(logs.warnings.single { it.contains("spent by another request") }, grantId)
        }
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
    @DisplayName("A failed grant write answers server_error at the revocation endpoint")
    fun failedGrantWriteIsReportedByTheRevocationEndpoint() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val tokens = redeem(issueCode(owner.client.clientId, UUID.randomUUID().toString()), owner).tokens()
        // グラントの失効の書き込みを確実に失敗させるため、テーブルを削除する
        transaction { exec("DROP TABLE revoked_grants") }

        val response = revoke(tokens.refresh, owner)

        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertEquals("server_error", response.jsonBody().getValue("error").jsonPrimitive.content)
    }

    @Test
    @DisplayName("Revoking an access token leaves the grant active")
    fun accessRevocationStaysPerJti() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val grantId = UUID.randomUUID().toString()
        val tokens = redeem(issueCode(owner.client.clientId, grantId), owner).tokens()

        assertEquals(HttpStatusCode.OK, revoke(tokens.access, owner).status)

        assertEquals(TokenStateFailure.REVOKED.left(), validator.validate(TokenKind.ACCESS, JWT.decode(tokens.access)))
        assertFalse(RevokedGrantRepository.isRevoked(grantId))
        assertEquals(HttpStatusCode.OK, refresh(tokens.refresh, owner).status)
    }

    @Test
    @DisplayName("Revoking the rotated token ends its grace window")
    fun revokingRotatedTokenEndsGraceWindow() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val first = redeem(issueCode(owner.client.clientId, UUID.randomUUID().toString()), owner).tokens()
        val rotated = refresh(first.refresh, owner).tokens()

        assertEquals(HttpStatusCode.OK, revoke(first.refresh, owner).status)

        assertInvalidGrant(refresh(first.refresh, owner))
        assertInvalidGrant(refresh(rotated.refresh, owner))
    }

    @Test
    @DisplayName("Revoking the new token ends the grace window of the rotated one")
    fun revokingNewTokenEndsGraceWindow() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val first = redeem(issueCode(owner.client.clientId, UUID.randomUUID().toString()), owner).tokens()
        val rotated = refresh(first.refresh, owner).tokens()

        assertEquals(HttpStatusCode.OK, revoke(rotated.refresh, owner).status)

        assertInvalidGrant(refresh(first.refresh, owner))
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

    @Test
    @DisplayName("Reusing a legacy token after the grace window stays per jti")
    fun legacyReuseStaysPerJti() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val legacy = legacyRefreshToken(owner.client.clientId)
        val rotated = refresh(legacy, owner).tokens()

        cacheNow += graceWindowEnd
        assertInvalidGrant(refresh(legacy, owner))

        assertAccessTokenValid(rotated.access)
        assertEquals(HttpStatusCode.OK, refresh(rotated.refresh, owner).status)
    }

    @Test
    @DisplayName("Revoking a rotated legacy token ends its grace window")
    fun revokingRotatedLegacyTokenEndsGraceWindow() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val legacy = legacyRefreshToken(owner.client.clientId)
        refresh(legacy, owner).tokens()

        assertEquals(HttpStatusCode.OK, revoke(legacy, owner).status)

        assertInvalidGrant(refresh(legacy, owner))
    }

    @Test
    @DisplayName("Revoking the new token ends the grace window of a rotated legacy token")
    fun revokingNewTokenEndsLegacyGraceWindow() = oauthTestApplication {
        val owner = registerClient(ClientType.CONFIDENTIAL)
        val legacy = legacyRefreshToken(owner.client.clientId)
        val rotated = refresh(legacy, owner).tokens()

        assertEquals(HttpStatusCode.OK, revoke(rotated.refresh, owner).status)

        assertInvalidGrant(refresh(legacy, owner))
    }
}
