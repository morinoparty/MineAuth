package party.morino.mineauth.core.web.ratelimit

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import party.morino.mineauth.core.file.data.RateLimitConfig
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.router.auth.oauth.ClientAuthFailure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * OAuthエンドポイントの流量制限と失敗時のバックオフをまとめたクラス
 *
 * 設定から作るのでConfigLoaderがKoinに登録し、/mineauth reload で新しい上限を適用してカウンターをリセットする。
 * ルーターは設定の変更に追従するため、保持せずリクエストごとにKoinから取得すること
 *
 * @param config 流量制限の設定
 * @param trustedProxies 解析済みのrateLimit.trustedProxies
 * @param logger 設定の誤りを管理者に知らせるロガー
 * @param clock 現在時刻（ミリ秒）の取得関数（テストで時間を制御するために注入可能）
 */
class OAuthRateLimits(
    private val config: RateLimitConfig,
    private val trustedProxies: List<CidrRange>,
    private val logger: Logger,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val tokenRequestsPerIp = limiterOf(config.tokenRequestsPerMinutePerIp)
    private val tokenRequestsPerClient = limiterOf(config.tokenRequestsPerMinutePerClient)
    private val loginAttemptsPerIp = limiterOf(config.loginAttemptsPerMinutePerIp)

    private val clientAuthFailuresPerIp =
        backoffOf(CLIENT_AUTH_FREE_FAILURES, CLIENT_AUTH_MAX_DELAY, CLIENT_AUTH_IDLE_EXPIRY)
    private val loginFailuresPerIp =
        backoffOf(LOGIN_IP_FREE_FAILURES, LOGIN_IP_MAX_DELAY, LOGIN_IP_IDLE_EXPIRY)
    private val loginFailuresPerUsername =
        backoffOf(LOGIN_USERNAME_FREE_FAILURES, LOGIN_USERNAME_MAX_DELAY, LOGIN_USERNAME_IDLE_EXPIRY)

    // ユーザー名のブロックを適用しない、ログインに成功したことのある接続元とユーザー名の組
    private val knownLoginSources =
        if (config.enabled) KnownLoginSources(KNOWN_LOGIN_SOURCE_RETENTION, clock) else null

    // 設定の誤りを1回の読み込みにつき1度だけ知らせるためのフラグ（リクエストごとに出すとログが溢れる）
    private val untrustedForwarderWarned = AtomicBoolean(false)
    private val trustedPeerWithoutHeaderWarned = AtomicBoolean(false)

    /**
     * 値が0以下の制限や無効な設定では制限を作らない
     *
     * @param requestsPerMinute 1分あたりの許可回数
     * @return 制限。無効な場合はnull
     */
    private fun limiterOf(requestsPerMinute: Int): RateLimiter? =
        if (config.enabled && requestsPerMinute > 0) RateLimiter(requestsPerMinute, clock) else null

    /**
     * バックオフを作る。流量制限全体が無効の場合は作らない
     *
     * @param freeFailures ブロックせずに許す失敗の回数
     * @param maxDelay ブロック時間の上限
     * @param idleExpiry 最後の失敗からキーを忘れるまでの時間
     * @return バックオフ。無効な場合はnull
     */
    private fun backoffOf(freeFailures: Int, maxDelay: Duration, idleExpiry: Duration): FailureBackoff? =
        if (config.enabled) FailureBackoff(freeFailures, BASE_DELAY, maxDelay, idleExpiry, clock) else null

    /**
     * リクエストのクライアントを解決する
     * trustedProxiesが実際の構成と合っていないと、全員が同じキーになるかクライアントがキーを選べてしまうので、
     * 気づけるようにそれぞれ一度だけ警告する
     *
     * Argon2Gateは流量制限が無効でもこのキーで接続元ごとの枠を割り当てるので、キーは常に同じ方法で解決し、
     * 構成の誤りの警告も有効・無効に関わらず出す（無効でも、全員が同じキーになるとハッシュの枠を共有してしまう）
     *
     * @param peer TCP接続の相手（call.request.local.remoteAddress）
     * @param forwardedFor X-Forwarded-Forヘッダーの値。無い場合はnull
     * @return クライアントのキー
     */
    fun resolveClientIp(peer: String, forwardedFor: String?): ClientIpKey {
        val trustedPeer = ClientIpResolver.isTrustedPeer(peer, trustedProxies)
        if (forwardedFor != null && !trustedPeer) {
            warnAboutUntrustedForwarder(peer)
        }
        if (forwardedFor == null && trustedPeer) {
            warnAboutTrustedPeerWithoutForwardedFor(peer)
        }
        return ClientIpResolver.resolve(peer, forwardedFor, trustedProxies)
    }

    /**
     * 認証に成功したトークンリクエストを、ちょうど1つのバケットで数える
     *
     * 認証前に数えると、client_idを知っているだけの第三者が正規のクライアントのバケットを使い切れてしまう。
     * Confidentialクライアントはシークレットで本人と確認できるのでclient_idごとに数え、
     * 同じIPから多数の利用者のトークンを更新するバックエンドを巻き込まないよう、IPごとのバケットは使わない。
     * Publicクライアントは誰でも名乗れるので、client_idではなくクライアントIPごとに数える
     *
     * @param ip クライアントのキー
     * @param client 認証済みのクライアント
     * @return 許可された場合はRight、制限中の場合はLeft
     */
    fun acquireTokenRequest(ip: ClientIpKey, client: ClientData): Either<RateLimited, Unit> {
        val acquired = when (client) {
            // DBに保存されたclient_idをそのまま使う（照合の大文字小文字の扱いがDBで異なっても同じキーになる）
            is ClientData.ConfidentialClientData -> tokenRequestsPerClient?.tryAcquire(client.clientId)
            is ClientData.PublicClientData -> tokenRequestsPerIp?.tryAcquire(ip.value)
        }
        return acquired ?: Unit.right()
    }

    /**
     * クライアント認証を実行してよいかを確認する
     * 読み取りだけで何も消費しないので、Argon2を実行する前に確認する
     *
     * @param ip クライアントのキー
     * @return 実行してよい場合はRight、ブロック中の場合はLeft
     */
    fun checkClientAuthentication(ip: ClientIpKey): Either<RateLimited, Unit> =
        limitedAfter(clientAuthFailuresPerIp?.retryAfterSeconds(ip.value))

    /**
     * クライアント認証の失敗を記録する
     * 存在しないclient_idと一致しなかったシークレットだけを数える。形式エラーやシークレットの欠如、
     * 混雑で照合しなかった場合は認証情報を推測する試行ではないので数えない
     * 成功してもリセットしない（有効なクライアントを持つ攻撃者が、推測の合間にカウンターを戻せないようにする）
     *
     * @param ip クライアントのキー
     * @param failure クライアント認証の失敗情報
     */
    fun recordClientAuthFailure(ip: ClientIpKey, failure: ClientAuthFailure) {
        if (failure.countsAsFailedAttempt) {
            clientAuthFailuresPerIp?.recordFailure(ip.value)
        }
    }

    /**
     * パスワードログインの試行を確認して数える
     *
     * 読み取りだけのバックオフを先に確認し、評価しない試行で回数のバケットを減らさないようにする。
     * 両方のバックオフに掛かっている場合は、片方が解除されても再試行できないので長い方の時間を返す
     *
     * ユーザー名のバックオフは、そのユーザー名でログインに成功したことのある接続元を除く全ての接続元に適用する。
     * プレイヤー名は公開情報なので、全員に適用すると、ブロックが切れるたびに1回失敗するだけで本人を締め出し続けられる。
     * 成功の記録は正しいパスワードを知っている人にしか作れないので、接続元を次々に変えてもブロックは迂回できない。
     * 除外された接続元も、IPごとのバックオフと試行回数のバケットには従い、失敗すればユーザー名とIPの両方に数えられる
     *
     * @param ip クライアントのキー
     * @param username リクエストで提示されたユーザー名
     * @return 評価してよい場合はRight、制限中の場合はLeft
     */
    fun acquireLoginAttempt(ip: ClientIpKey, username: String): Either<RateLimited, Unit> {
        // 以前にこのユーザー名でログインに成功した接続元には、ユーザー名のブロックを適用しない
        val knownSource = knownLoginSources?.isKnown(ip, username) ?: false
        val usernameBackoffSeconds =
            if (knownSource) null else loginFailuresPerUsername?.retryAfterSeconds(RateLimitKeys.username(username))
        val backoffSeconds = listOfNotNull(
            loginFailuresPerIp?.retryAfterSeconds(ip.value),
            usernameBackoffSeconds
        ).maxOrNull()
        return if (backoffSeconds != null) {
            RateLimited(backoffSeconds).left()
        } else {
            loginAttemptsPerIp?.tryAcquire(ip.value) ?: Unit.right()
        }
    }

    /**
     * ログインの失敗を記録する
     *
     * @param ip クライアントのキー
     * @param username リクエストで提示されたユーザー名
     */
    fun recordLoginFailure(ip: ClientIpKey, username: String) {
        loginFailuresPerIp?.recordFailure(ip.value)
        loginFailuresPerUsername?.recordFailure(RateLimitKeys.username(username))
    }

    /**
     * ログインの成功を記録する
     * IPのカウンターは戻さない（有効なアカウントを持つ攻撃者が、推測の合間にカウンターを戻せないようにする）
     * 接続元とユーザー名の組を覚え、以後この接続元からのこのユーザー名の試行にはユーザー名のブロックを適用しない
     *
     * @param ip クライアントのキー
     * @param username リクエストで提示されたユーザー名
     */
    fun recordLoginSuccess(ip: ClientIpKey, username: String) {
        loginFailuresPerUsername?.recordSuccess(RateLimitKeys.username(username))
        knownLoginSources?.recordSuccess(ip, username)
    }

    /**
     * バックオフの残り時間をEitherに変換する
     *
     * @param retryAfterSeconds 解除までの秒数。ブロックされていない場合はnull
     * @return ブロックされていない場合はRight、ブロック中の場合はLeft
     */
    private fun limitedAfter(retryAfterSeconds: Long?): Either<RateLimited, Unit> =
        retryAfterSeconds?.let { RateLimited(it).left() } ?: Unit.right()

    /**
     * 信頼していない接続元がX-Forwarded-Forを送ってきたことを一度だけ警告する
     * ヘッダーの値はリクエスト由来でログを偽装できるので、接続元のアドレスだけを出力する
     *
     * @param peer TCP接続の相手
     */
    private fun warnAboutUntrustedForwarder(peer: String) {
        if (!untrustedForwarderWarned.compareAndSet(false, true)) return
        logger.warning(
            "Received X-Forwarded-For from $peer, which is not in rateLimit.trustedProxies, so the header was ignored. " +
                "If this address is your reverse proxy, add it to rateLimit.trustedProxies in config.json; " +
                "otherwise all clients behind it share one rate limit and the same two password-hashing slots. " +
                "This warning is logged once per config load."
        )
    }

    /**
     * 信頼している接続元がX-Forwarded-Forを付けずに接続してきたことを一度だけ警告する
     *
     * ヘッダーを追加しないTCPのトンネルやポート転送がこのアドレスから接続していると、
     * 転送されたクライアント全員が同じキーになり、クライアントが自分で書いたX-Forwarded-Forも信じてしまう。
     * どちらも警告なしに起きるので、ヘッダーの無いリクエストを手がかりに知らせる
     *
     * @param peer TCP接続の相手
     */
    private fun warnAboutTrustedPeerWithoutForwardedFor(peer: String) {
        if (!trustedPeerWithoutHeaderWarned.compareAndSet(false, true)) return
        logger.warning(
            "Received a request without X-Forwarded-For from $peer, which is in rateLimit.trustedProxies. " +
                "If a TCP tunnel or port forwarder that does not add the header (such as playit.gg, frp, ngrok tcp, " +
                "ssh -R, socat, or Docker's userland proxy for IPv6) connects from this address, all clients behind it " +
                "share one rate limit and the same two password-hashing slots, and can choose their own X-Forwarded-For. " +
                "In that case set rateLimit.trustedProxies in config.json to [] or to the exact address of your reverse proxy. " +
                "This warning is logged once per config load."
        )
    }

    private companion object {
        // 全てのバックオフで共通の最初のブロック時間
        val BASE_DELAY = 1.seconds

        // クライアント認証の失敗（IPごと）: シークレットの設定ミスなどの正規の失敗には余裕を持たせる
        const val CLIENT_AUTH_FREE_FAILURES = 10
        val CLIENT_AUTH_MAX_DELAY = 5.minutes
        val CLIENT_AUTH_IDLE_EXPIRY = 1.hours

        // ログインの失敗（IPごと）: パスワードの推測を止める本命の制限
        const val LOGIN_IP_FREE_FAILURES = 5
        val LOGIN_IP_MAX_DELAY = 15.minutes
        val LOGIN_IP_IDLE_EXPIRY = 30.minutes

        // ログインの失敗（ユーザー名ごと）: 接続元を変えながら1つのアカウントに推測を続けることを抑える制限
        // ログインに成功したことのある接続元には適用せず、上限も短くして、公開されたプレイヤー名で本人を締め出せないようにしている
        // （本人も新しい接続元からは、攻撃者が更新し続ける限り最大60秒ずつ待たされる）
        const val LOGIN_USERNAME_FREE_FAILURES = 20
        val LOGIN_USERNAME_MAX_DELAY = 60.seconds
        val LOGIN_USERNAME_IDLE_EXPIRY = 1.hours

        // ログインに成功した接続元とユーザー名の組を覚えておく期間（最後の成功から数える）
        val KNOWN_LOGIN_SOURCE_RETENTION = 30.days
    }
}
