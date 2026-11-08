package party.morino.mineauth.core.web.ratelimit

import arrow.core.left
import arrow.core.right
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.router.auth.oauth.ClientAuthFailure
import party.morino.mineauth.core.web.router.auth.oauth.ClientAuthFailureKind
import party.morino.mineauth.core.web.router.auth.oauth.OAuthErrorCode
import kotlin.test.assertEquals

/**
 * OAuthRateLimitsのテスト
 * 設定の反映と、失敗の数え方（どのキーに記録し、何でリセットするか）を確認する
 */
class OAuthRateLimitsTest {
    private var now = 0L
    private val logHandler = RecordingLogHandler()
    private val invalidClient = ClientAuthFailure(
        OAuthErrorCode.INVALID_CLIENT,
        "Client authentication failed",
        ClientAuthFailureKind.CREDENTIALS_REJECTED
    )
    private val publicClient = ClientData.PublicClientData("public", "Public", emptyList())
    private val confidentialClient = ClientData.ConfidentialClientData("confidential", "Confidential", emptyList(), "hash")

    private fun limits(config: RateLimitConfig = RateLimitConfig()) =
        OAuthRateLimits(config, emptyList(), logHandler.newLogger(), clock = { now })

    private fun ip(lastOctet: Int) = ClientIpKey("203.0.113.$lastOctet")

    @Test
    @DisplayName("Public clients are counted per IP and confidential clients per client_id")
    fun tokenRequestsSpendOneBucket() {
        val limits = limits(RateLimitConfig(tokenRequestsPerMinutePerIp = 1, tokenRequestsPerMinutePerClient = 1))

        // Publicクライアントは誰でも名乗れるので、別のIPからの利用で正規の利用者を締め出せない
        assertEquals(Unit.right(), limits.acquireTokenRequest(ip(1), publicClient))
        assertEquals(RateLimited(60).left(), limits.acquireTokenRequest(ip(1), publicClient))
        assertEquals(Unit.right(), limits.acquireTokenRequest(ip(2), publicClient))

        // Confidentialクライアントはclient_idで数え、IPのバケットは使わない
        assertEquals(Unit.right(), limits.acquireTokenRequest(ip(1), confidentialClient))
        assertEquals(RateLimited(60).left(), limits.acquireTokenRequest(ip(3), confidentialClient))
    }

    @Test
    @DisplayName("Client authentication allows 10 failures per IP and counts only rejected credentials")
    fun clientAuthBackoff() {
        val limits = limits()

        // 照合していない失敗と、混雑で照合しなかった失敗は数えない
        repeat(50) {
            limits.recordClientAuthFailure(ip(1), ClientAuthFailure(OAuthErrorCode.INVALID_REQUEST, "bad"))
            limits.recordClientAuthFailure(
                ip(1),
                ClientAuthFailure(OAuthErrorCode.INVALID_CLIENT, "Client authentication required")
            )
            limits.recordClientAuthFailure(
                ip(1),
                ClientAuthFailure(OAuthErrorCode.TEMPORARILY_UNAVAILABLE, "busy", ClientAuthFailureKind.VERIFIER_BUSY)
            )
        }
        assertEquals(Unit.right(), limits.checkClientAuthentication(ip(1)))

        repeat(10) { limits.recordClientAuthFailure(ip(1), invalidClient) }
        assertEquals(Unit.right(), limits.checkClientAuthentication(ip(1)))

        limits.recordClientAuthFailure(ip(1), invalidClient)
        assertEquals(RateLimited(1).left(), limits.checkClientAuthentication(ip(1)))
        // 別のIPには影響しない
        assertEquals(Unit.right(), limits.checkClientAuthentication(ip(2)))
    }

    @Test
    @DisplayName("Login failures block the IP after 5 and success does not reset it")
    fun loginIpBackoffIsNotResetBySuccess() {
        val limits = limits()

        // ユーザー名ごとの猶予に掛からないよう、毎回別の名前で失敗する
        repeat(5) { limits.recordLoginFailure(ip(1), "user$it") }
        assertEquals(Unit.right(), limits.acquireLoginAttempt(ip(1), "steve"))

        limits.recordLoginFailure(ip(1), "user5")
        limits.recordLoginSuccess(ip(1), "user5")

        assertEquals(RateLimited(1).left(), limits.acquireLoginAttempt(ip(1), "steve"))
        assertEquals(Unit.right(), limits.acquireLoginAttempt(ip(2), "steve"))
    }

    @Test
    @DisplayName("Username failures block every source after 20 and success resets them")
    fun loginUsernameBackoffIsResetBySuccess() {
        val limits = limits()

        // IPごとの猶予に掛からないよう、毎回別のIPから失敗する
        repeat(20) { limits.recordLoginFailure(ip(it), "Steve") }
        assertEquals(Unit.right(), limits.acquireLoginAttempt(ip(0), "steve"))

        limits.recordLoginFailure(ip(20), "STEVE")
        // 失敗した接続元も、一度も試していない接続元も止まる
        assertEquals(RateLimited(1).left(), limits.acquireLoginAttempt(ip(0), "steve"))
        assertEquals(RateLimited(1).left(), limits.acquireLoginAttempt(ip(99), "steve"))

        // 成功するとユーザー名のカウンターが戻り、他の接続元も評価される
        limits.recordLoginSuccess(ip(0), "steve")
        assertEquals(Unit.right(), limits.acquireLoginAttempt(ip(99), "steve"))
    }

    @Test
    @DisplayName("Renewing the username block cannot keep the owner out where they logged in before")
    fun usernameBlockDoesNotStopKnownSource() {
        val limits = limits()
        val owner = ip(1)
        limits.recordLoginSuccess(owner, "Steve")

        // 攻撃者は毎回新しい接続元から失敗し、ユーザー名のブロックを上限の60秒まで伸ばして更新し続ける
        var attempt = 0
        fun attackerFails() = limits.recordLoginFailure(ClientIpKey("2001:db8:0:${attempt++}::"), "steve")
        repeat(30) { attackerFails() }
        // 本人がこの接続元で打ち間違えても、ユーザー名のブロックからは除外されたままになる
        limits.recordLoginFailure(owner, "steve")

        repeat(240) { round ->
            attackerFails()
            now += 30_000
            assertEquals(Unit.right(), limits.acquireLoginAttempt(owner, "steve"), "round $round")
            // 成功したことのない接続元は、同じ時点で止まる
            assertEquals(RateLimited(30).left(), limits.acquireLoginAttempt(ip(2), "steve"), "round $round")
            now += 30_000
        }
    }

    @Test
    @DisplayName("Rotating sources cannot bypass the username block")
    fun freshSourceIsHeldByUsernameBlock() {
        val limits = limits()
        // 他のユーザー名やこの接続元での成功は、別の組の除外にはならない
        limits.recordLoginSuccess(ip(1), "alex")
        limits.recordLoginSuccess(ip(2), "steve")

        repeat(21) { limits.recordLoginFailure(ClientIpKey("2001:db8:0:$it::"), "steve") }

        // 失敗の記録が無い新しい接続元でも、ユーザー名のブロック中は評価しない
        assertEquals(RateLimited(1).left(), limits.acquireLoginAttempt(ClientIpKey("2001:db8:0:21::"), "steve"))
        assertEquals(RateLimited(1).left(), limits.acquireLoginAttempt(ip(1), "steve"))
        assertEquals(Unit.right(), limits.acquireLoginAttempt(ip(2), "steve"))
    }
}
