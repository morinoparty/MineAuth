package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.opentelemetry.api.common.Attributes
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.components.auth.TokenData
import party.morino.mineauth.core.web.components.auth.UserInfoResponse
import party.morino.mineauth.core.web.ratelimit.ClientIpKey
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import party.morino.mineauth.core.web.ratelimit.RateLimited
import party.morino.mineauth.core.web.ratelimit.clientIpOf
import party.morino.mineauth.core.web.ratelimit.respondClientAuthFailure
import party.morino.mineauth.core.web.ratelimit.respondRateLimited
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.authorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.authenticateClient
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractClientCredentials
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.validateCodeVerifier
import party.morino.mineauth.core.model.GRANT_ID_CLAIM
import party.morino.mineauth.core.model.TokenLifetimes
import party.morino.mineauth.core.model.grantId
import party.morino.mineauth.core.repository.RevokedGrantRepository
import party.morino.mineauth.core.repository.RevokedTokenError
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenRevocationTime
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.web.telemetry.withSpan
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.*

object TokenRouter: KoinComponent {
    // RFC 9700 Section 4.14.2: reuse grace period用のレスポンスキャッシュ
    // RevokeRouterが無効化するインスタンスと必ず一致させるため、objectに固定せず呼び出しごとにKoinから解決する
    private val refreshTokenResponseCache: RefreshTokenResponseCache get() = get()
    private const val EXPIRES_IN = 300
    // RFC 6749 Section 4.1.2: 認可コードの最大有効期間（10分推奨）
    private const val AUTHORIZATION_CODE_LIFETIME_MS = 10 * 60 * 1000L

    fun Route.tokenRouter() {
        post("/token") {
            // トークンエンドポイント全体をスパンで計測する
            withSpan("oauth.token") { span ->
                // 設定は/mineauth reloadで置き換わるため、保持せずリクエストごとに取得する
                // 回数のバケットはクライアント認証に成功してから数える（authenticateWithinLimitsを参照）
                val rateLimits = get<OAuthRateLimits>()
                val clientIp = rateLimits.clientIpOf(call)

                val formParameters = call.receiveParameters()
                // RFC 6749 Section 3.2: 空値は省略扱い、重複はinvalid_request
                val grantType = formParameters.readSingleParameter("grant_type").getOrElse { failure ->
                    call.respondOAuthError(failure.errorCode, failure.message)
                    return@withSpan
                }

                // RFC 6749 Section 5.2: grant_type欠如はinvalid_request
                if (grantType == null) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: grant_type")
                    return@withSpan
                }
                span.setAttribute(TelemetryAttributes.OAUTH_GRANT_TYPE, grantType)

                // クレデンシャル抽出（Basic認証 + フォームパラメータ、併用禁止チェック含む）
                val credentials = when (val result = extractClientCredentials(formParameters)) {
                    is Either.Left -> {
                        call.respondOAuthError(result.value.errorCode, result.value.message)
                        return@withSpan
                    }
                    is Either.Right -> result.value
                }
                span.setAttribute(TelemetryAttributes.CLIENT_ID, credentials.clientId)

                if (grantType == "refresh_token") {
                    //refresh_tokenの処理 https://tools.ietf.org/html/rfc6749#section-6
                    // クライアント認証（Confidential/Public両対応）
                    val authenticated = authenticateWithinLimits(rateLimits, clientIp, credentials).getOrElse { limited ->
                        call.respondRateLimited(limited)
                        return@withSpan
                    }
                    val clientData = when (authenticated) {
                        is Either.Left -> {
                            call.respondClientAuthFailure(authenticated.value)
                            return@withSpan
                        }
                        is Either.Right -> authenticated.value
                    }
                    val clientId = clientData.clientId

                    val refreshToken = formParameters.readSingleParameter("refresh_token").getOrElse { failure ->
                        call.respondOAuthError(failure.errorCode, failure.message)
                        return@withSpan
                    }
                    if (refreshToken == null) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: refresh_token")
                        return@withSpan
                    }

                    // リフレッシュトークンの検証（署名・有効期限・token_type）
                    val verified = withSpan("oauth.refresh_token.verify") { verifyAndDecodeRefreshToken(refreshToken) }
                    if (verified == null) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid or expired refresh token")
                        return@withSpan
                    }
                    span.setAttribute(TelemetryAttributes.OAUTH_SCOPE, verified.authorizedData.scope)
                    span.setAttribute(TelemetryAttributes.PLAYER_UUID, verified.authorizedData.uniqueId.toString())

                    // クライアントIDの一致を確認
                    if (verified.authorizedData.clientId != clientId) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Refresh token was not issued to this client")
                        return@withSpan
                    }

                    // リフレッシュトークン内のスコープを再検証（許可リスト変更に対応）
                    if (!OAuthScope.isValid(verified.authorizedData.scope)) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_SCOPE, "Refresh token contains invalid scope(s)")
                        return@withSpan
                    }

                    // 失効済みグラントのトークンはgrace期間内でも拒否する
                    // キャッシュは応答を再現するだけで失効状態を見ないため、照会より先に確認する
                    // 読み取りに失敗した場合は失効済みと扱わずserver_errorで応答する（ensureGrantActive）
                    val grantId = verified.authorizedData.grantId
                    if (grantId != null) {
                        ensureGrantActive(grantId, clientId).onLeft { failure ->
                            call.respondOAuthError(failure.errorCode, failure.message)
                            return@withSpan
                        }
                    }

                    // RFC 9700 Section 4.14.2: reuse grace period 付きローテーション
                    // 同一リフレッシュトークンでの並行・grace期間内のリクエストには
                    // キャッシュ済みの同一レスポンスを返す（べき等化）
                    // 実際のローテーションと発行はtokenIdごとに最初の1リクエストのみが実行する
                    // grace期間の判定と失効日時の記録に同じ時計を使うため、リクエスト内では同じインスタンスを使う
                    val responseCache = refreshTokenResponseCache
                    val result = responseCache.getOrIssue(
                        verified.tokenId,
                        // 旧形式のトークンは照合するgrant_idを持たないので、キャッシュから返す応答は含まれる新トークンのグラントで確認する
                        // このリクエストが行ったローテーションの応答は確認しない（rejectIfResponseGrantRevokedを参照）
                        verifyCached = { response ->
                            if (grantId == null) rejectIfResponseGrantRevoked(response, clientId) else response.right()
                        }
                    ) {
                        rotateRefreshToken(verified, clientId, responseCache)
                    }

                    when (result) {
                        is Either.Left -> {
                            call.respondOAuthError(result.value.errorCode, result.value.message)
                        }
                        is Either.Right -> {
                            // RFC 6749 Section 5.1: トークンレスポンスにキャッシュ禁止ヘッダーを付与
                            // （キャッシュヒット時のレスポンスにも同じヘッダーを付与する）
                            call.response.header(HttpHeaders.CacheControl, "no-store")
                            call.response.header(HttpHeaders.Pragma, "no-cache")
                            call.respond(HttpStatusCode.OK, result.value)
                        }
                    }
                }else if (grantType == "authorization_code") {
                    //authorization_codeの処理 https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.3
                    // 期限切れ認可コードの遅延クリーンアップ（リクエスト契機で実行）
                    OAuthRouter.cleanupExpiredCodes(AUTHORIZATION_CODE_LIFETIME_MS)
                    val (code, redirectUri, codeVerifier) = either {
                        Triple(
                            formParameters.readSingleParameter("code").bind(),
                            formParameters.readSingleParameter("redirect_uri").bind(),
                            formParameters.readSingleParameter("code_verifier").bind()
                        )
                    }.getOrElse { failure ->
                        call.respondOAuthError(failure.errorCode, failure.message)
                        return@withSpan
                    }
                    val clientId = credentials.clientId

                    // RFC 6749 Section 5.2: 必須パラメータの欠如はinvalid_requestで返す
                    // 認可コード参照より先にチェックし、不要なデータ消費を防ぐ
                    val missingParams = buildList {
                        if (code == null) add("code")
                        if (redirectUri == null) add("redirect_uri")
                        if (codeVerifier == null) add("code_verifier")
                    }
                    if (missingParams.isNotEmpty()) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameters: ${missingParams.joinToString(", ")}")
                        return@withSpan
                    }

                    // クライアント認証（Confidential/Public両対応）
                    val authenticated = authenticateWithinLimits(rateLimits, clientIp, credentials).getOrElse { limited ->
                        call.respondRateLimited(limited)
                        return@withSpan
                    }
                    val clientData = when (authenticated) {
                        is Either.Left -> {
                            call.respondClientAuthFailure(authenticated.value)
                            return@withSpan
                        }
                        is Either.Right -> authenticated.value
                    }

                    // 使用済みの記録の遅延クリーンアップ（リクエスト契機で実行）
                    get<RedeemedCodeStore>().cleanupExpired()

                    // 取得・削除・使用済みの記録を原子的に行い、並行リクエストによる二重使用とリプレイの見逃しを防止
                    // nullチェック済みのためnon-null変数に再代入
                    val validCode = code!!
                    val data = redeemCode(validCode) ?: run {
                        // RFC 6749 Section 4.1.2: 再使用された認可コードは拒否し、発行済みのトークンも失効させる
                        // リプレイであることを応答から判別できないよう、未知のコードと同じエラーで応答する
                        revokeGrantOnCodeReplay(validCode, clientData.clientId)
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The authorization code is invalid or expired")
                        return@withSpan
                    }
                    span.setAttribute(TelemetryAttributes.OAUTH_SCOPE, data.scope)
                    span.setAttribute(TelemetryAttributes.PLAYER_UUID, data.uniqueId.toString())

                    // RFC 6749 Section 4.1.2: 認可コードの有効期限チェック（最大10分）
                    val codeAge = System.currentTimeMillis() - data.authTime
                    if (codeAge > AUTHORIZATION_CODE_LIFETIME_MS) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The authorization code has expired")
                        return@withSpan
                    }

                    // nullチェック済みのためnon-null変数に再代入
                    val validRedirectUri = redirectUri!!
                    val validCodeVerifier = codeVerifier!!

                    if (data.clientId != clientId || data.redirectUri != validRedirectUri) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid client_id or redirect_uri")
                        return@withSpan
                    }

                    if (!validateCodeVerifier(data.codeChallenge, validCodeVerifier)) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The code_verifier is invalid")
                        return@withSpan
                    }

                    val token = withSpan("jwt.issue", attributes = tokenTypeAttributes("access")) {
                        issueToken(data, clientId)
                    }
                    val refreshToken = withSpan("jwt.issue", attributes = tokenTypeAttributes("refresh")) {
                        issueRefreshToken(data, clientId)
                    }
                    // scopeに"openid"が含まれる場合のみID Tokenを発行（OIDC準拠）
                    val idToken = if (data.scope.contains("openid")) {
                        withSpan("jwt.issue", attributes = tokenTypeAttributes("id")) {
                            issueIdToken(data, clientId, token)
                        }
                    } else null

                    // RFC 6749 Section 5.1: トークンレスポンスにキャッシュ禁止ヘッダーを付与
                    call.response.header(HttpHeaders.CacheControl, "no-store")
                    call.response.header(HttpHeaders.Pragma, "no-cache")
                    call.respond(
                        HttpStatusCode.OK, TokenData(
                            accessToken = token,
                            tokenType = "Bearer",
                            expiresIn = EXPIRES_IN,
                            refreshToken = refreshToken,
                            idToken = idToken,
                            scope = data.scope
                        )
                    )
                }else{
                    call.respondOAuthError(OAuthErrorCode.UNSUPPORTED_GRANT_TYPE, "The grant_type is not supported")
                }
            }
        }
    }

    /**
     * バックオフを確認してからクライアントを認証し、結果に応じて失敗の記録か回数のバケットの消費を行う
     *
     * バックオフの確認は読み取りだけなので、Argon2の実行より前に行う。
     * 回数のバケットは認証に成功したリクエストだけで数える。認証前に数えると、client_idを知っているだけの第三者が
     * 正規のクライアントのバケットを使い切れてしまう（Publicクライアントの認証は誰が送っても成功するので、認証でも防げない）。
     * 推測にあたる失敗はバックオフが止めるので、認証に失敗したリクエストはバケットで数えない
     *
     * @param rateLimits 現在の設定の流量制限
     * @param clientIp クライアントのキー
     * @param credentials 抽出済みのクレデンシャル
     * @return 制限中の場合はLeft、それ以外はクライアント認証の結果を持つRight
     */
    private suspend fun authenticateWithinLimits(
        rateLimits: OAuthRateLimits,
        clientIp: ClientIpKey,
        credentials: ClientCredentials
    ): Either<RateLimited, Either<ClientAuthFailure, ClientData>> = either {
        rateLimits.checkClientAuthentication(clientIp).bind()
        // 接続元のキーでArgon2Gateの枠を割り当て、1つの接続元の大量のリクエストが他の接続元の検証を待たせないようにする
        val authenticated = withSpan("oauth.client.authenticate") { authenticateClient(credentials, clientIp) }
        when (authenticated) {
            // 推測にあたる失敗だけがバックオフに数えられる（記録するかはrecordClientAuthFailureが判断する）
            is Either.Left -> rateLimits.recordClientAuthFailure(clientIp, authenticated.value)
            // 認証済みのクライアントの種類に応じて、ちょうど1つのバケットを消費する
            is Either.Right -> rateLimits.acquireTokenRequest(clientIp, authenticated.value).bind()
        }
        authenticated
    }

    /**
     * jwt.issueスパン用のトークン種別属性を生成する
     *
     * @param tokenType トークンの種類（access / refresh / id）
     * @return トークン種別属性
     */
    private fun tokenTypeAttributes(tokenType: String): Attributes =
        Attributes.of(TelemetryAttributes.TOKEN_TYPE, tokenType)

    private fun issueToken(
        data: AuthorizedData, clientId: String?
    ): String = JwtProvider.newToken(TokenKind.ACCESS).withIssuer(get<JWTConfigData>().issuer)
        // RFC 9068 Section 2.2: JWTアクセストークンにsubは必須（token_typeを持つのでID Tokenとは誤認されない）
        .withSubject(data.uniqueId.toString())
        .withAudience(data.clientId)
        .withNotBefore(Date(System.currentTimeMillis()))
        .withExpiresAt(Date(System.currentTimeMillis() + EXPIRES_IN * 1_000.toLong()))
        .withIssuedAt(Date(System.currentTimeMillis()))
        .withJWTId(UUID.randomUUID().toString())
        .withClaim("client_id", clientId).withClaim("playerUniqueId", data.uniqueId.toString()).withClaim("scope", data.scope).withClaim("state", data.state).withClaim("scope", data.scope)
        .withGrantId(data.grantId)
        .sign(JwtProvider.algorithm)

    private fun issueRefreshToken(
        data: AuthorizedData, clientId: String?
    ): String = JwtProvider.newToken(TokenKind.REFRESH).withIssuer(get<JWTConfigData>().issuer)
        .withAudience(data.clientId)
        .withNotBefore(Date(System.currentTimeMillis()))
        .withExpiresAt(Date(System.currentTimeMillis() + TokenLifetimes.REFRESH_TOKEN.toMillis()))
        .withIssuedAt(Date(System.currentTimeMillis())).withJWTId(UUID.randomUUID().toString())
        .withClaim("client_id", clientId)
        .withClaim("playerUniqueId", data.uniqueId.toString())
        .withClaim("scope", data.scope)
        .withClaim("state", data.state)
        .withClaim("scope", data.scope)
        .withGrantId(data.grantId)
        .sign(JwtProvider.algorithm)

    /**
     * グラントIDがある場合だけgrant_idクレームを設定する
     *
     * @param grantId グラントID
     * @return JWTビルダー
     */
    private fun JWTCreator.Builder.withGrantId(grantId: String?): JWTCreator.Builder =
        grantId?.let { withClaim(GRANT_ID_CLAIM, it) } ?: this

    /**
     * 新しいグラントIDを生成する
     *
     * @return UUID形式のグラントID
     */
    private fun newGrantId(): String = UUID.randomUUID().toString()

    /**
     * 認可コードを使用済みにして、紐づく認可データを返す
     *
     * 削除と使用済みの記録を同じキーへの1回の原子操作で行う。
     * 同じコードの並行リクエストは操作の完了を待ってから未登録と判定するため、記録を必ず参照でき、リプレイを未知のコードと誤認しない。
     * 認可コードの検証より先に記録するので、検証に失敗した使用の後の再提示もリプレイとして扱う
     *
     * @param code 認可コード
     * @return 未使用のコードならグラントID付きの認可データ、未知または使用済みのコードならnull
     */
    private fun redeemCode(code: String): AuthorizedData? {
        val redeemedCodes = get<RedeemedCodeStore>()
        var redeemed: AuthorizedData? = null
        authorizedData.computeIfPresent(code) { _, data ->
            val grantId = data.grantId ?: newGrantId()
            redeemedCodes.record(code, RedeemedCode(grantId, data.clientId, data.authTime + AUTHORIZATION_CODE_LIFETIME_MS))
            redeemed = data.copy(grantId = grantId)
            // nullを返すとエントリが削除される
            null
        }
        return redeemed
    }

    /**
     * 認可コードのリプレイであればグラントを失効させる
     *
     * 別のクライアントが提示した場合は、そのクライアントが何を持っているかの証拠にならないうえ、
     * 第三者が他人のセッションを終わらせる手段になるので失効させない
     *
     * @param code 提示された認可コード（ログには出力しない）
     * @param clientId 認証済みのクライアントID
     */
    private suspend fun revokeGrantOnCodeReplay(code: String, clientId: String) {
        val redeemed = get<RedeemedCodeStore>().find(code) ?: return
        if (redeemed.clientId != clientId) return

        val logger = get<MineAuth>().logger
        logger.warning("Authorization code replay detected, revoking the grant: client_id=$clientId, grant_id=${redeemed.grantId}")
        RevokedGrantRepository.revoke(redeemed.grantId, clientId).onLeft { error ->
            // 失効に失敗してもリクエストは拒否する。呼び出し側が応答するinvalid_grantは変えない
            logger.severe("Failed to revoke the grant after authorization code replay: client_id=$clientId, grant_id=${redeemed.grantId}, error=$error")
        }
    }

    /**
     * リフレッシュトークンの再利用を検知したときにグラントを失効させる
     * 旧形式のトークンはグラントを持たないので、jti単位の拒否だけになる
     *
     * @param verified 署名検証済みのリフレッシュトークン（ログにはクレームの一部のみ出力する）
     * @param clientId 認証済みのクライアントID
     */
    private suspend fun revokeGrantOnRefreshTokenReuse(verified: VerifiedRefreshToken, clientId: String) {
        val grantId = verified.authorizedData.grantId ?: return

        val logger = get<MineAuth>().logger
        RevokedGrantRepository.revokeForReusedToken(grantId, clientId, verified.tokenId).fold(
            { error ->
                // 失効に失敗してもリクエストは拒否する。呼び出し側が応答するinvalid_grantは変えない
                logger.severe("Failed to revoke the grant after refresh token reuse: client_id=$clientId, grant_id=$grantId, error=$error")
            },
            { revoked ->
                if (revoked) {
                    logger.warning("Refresh token reuse detected, revoked the grant: client_id=$clientId, grant_id=$grantId")
                } else {
                    // 読み取った後に失効記録がクリーンアップで消えた。再利用を確認できないのでグラントは失効させない
                    logger.warning("The revocation record of a reused refresh token was gone, denied without revoking the grant: client_id=$clientId, grant_id=$grantId")
                }
            }
        )
    }

    /**
     * 旧形式のリフレッシュトークンへのキャッシュ済みの応答が、失効済みグラントのトークンでないことを確認する
     *
     * 旧形式のトークンはgrant_idを持たないためキャッシュの照会前に確認できないが、ローテーションで発行した新トークンはグラントを持つ。
     * 新トークンの失効でグラントが失効した後は、grace期間内の再提示にも使えないトークンを渡さずに拒否する
     *
     * キャッシュから返す応答にだけ適用し、そのリクエスト自身が行ったローテーションの応答には適用しない。
     * そのローテーションが作ったばかりのグラントは失効しようがない。また旧トークンの使用済みの記録はコミット済みなので、
     * ここで読み取りに失敗してserver_errorを返すと、クライアントは同じトークンで再試行できずにセッションを失う
     *
     * @param response キャッシュ済みのトークンレスポンス
     * @param clientId 認証済みのクライアントID（ログ用）
     * @return グラントが有効ならレスポンス、失効済みならinvalid_grant、読み取りの失敗時はserver_error
     */
    private suspend fun rejectIfResponseGrantRevoked(
        response: TokenData,
        clientId: String
    ): Either<TokenRotationFailure, TokenData> {
        val responseGrantId = JWT.decode(response.refreshToken).grantId() ?: return response.right()
        return ensureGrantActive(responseGrantId, clientId).map { response }
    }

    /**
     * refresh_tokenグラントで、グラントが失効していないことを確認する
     *
     * Bearer認証やイントロスペクションと違い、読み取りの失敗を失効済みとは扱わない。
     * RFC 6749 Section 5.2のinvalid_grantはトークンが無効だという表明なので、障害中に返すとクライアントが有効なリフレッシュトークンを捨て、
     * セッションが終わってしまう。読み取りに失敗した場合はserver_errorで応答し、何も失効させない
     *
     * @param grantId グラントID
     * @param clientId 認証済みのクライアントID（ログ用）
     * @return 有効ならUnit、失効済みならinvalid_grant、読み取りの失敗時はserver_error
     */
    private suspend fun ensureGrantActive(grantId: String, clientId: String): Either<TokenRotationFailure, Unit> =
        RevokedGrantRepository.checkRevoked(grantId).fold(
            { error ->
                get<MineAuth>().logger.severe(
                    "Failed to read the state of a grant, answered server_error: client_id=$clientId, grant_id=$grantId, error=$error"
                )
                stateReadFailure().left()
            },
            { revoked -> if (revoked) invalidRefreshToken().left() else Unit.right() }
        )

    /**
     * リフレッシュトークンをローテーションして新しいトークンを発行する
     * レスポンスキャッシュから、tokenIdごとに最初の1リクエストだけが実行する
     *
     * 失敗しうる処理（失効状態の読み取りと新しいトークンへの署名）を先に済ませ、旧トークンの失効の記録を最後に行う。
     * 記録のコミットの後に残るのは応答を返すことだけなので、署名の失敗などで旧トークンだけが使用済みになり、
     * クライアントの再試行が再利用として扱われることはない
     *
     * @param verified 署名検証済みのリフレッシュトークン
     * @param clientId 認証済みのクライアントID
     * @param responseCache grace期間の判定と失効日時の記録に使う時計を持つレスポンスキャッシュ
     * @return 発行したトークンレスポンス、または応答すべきエラー
     */
    private suspend fun rotateRefreshToken(
        verified: VerifiedRefreshToken,
        clientId: String,
        responseCache: RefreshTokenResponseCache
    ): Either<TokenRotationFailure, TokenData> = either {
        // キャッシュ未ヒットで使用済みのjtiは拒否する（再利用の検知もここで行う）
        ensureRefreshTokenUnused(verified, clientId, responseCache).bind()

        // グラントIDはローテーションをまたいで引き継ぐ
        // 旧形式のトークンからはここで新しいグラントを始める（そうしないと連なりがいつまでも失効の対象にならない）
        val rotatedData = verified.authorizedData.copy(grantId = verified.authorizedData.grantId ?: newGrantId())
        val token = withSpan("jwt.issue", attributes = tokenTypeAttributes("access")) {
            issueToken(rotatedData, clientId)
        }
        val newRefreshToken = withSpan("jwt.issue", attributes = tokenTypeAttributes("refresh")) {
            issueRefreshToken(rotatedData, clientId)
        }

        // RFC 6749 Section 10.4: リフレッシュトークンローテーション
        // 署名を済ませてから旧トークンを失効させる。失効を記録できなければ、署名済みのトークンは渡さずに捨てる
        withSpan("oauth.refresh_token.rotate") {
            revokeOldRefreshToken(verified, clientId, responseCache)
        }.bind()

        // refresh_token使用時はID Tokenを発行しない（OIDC一般慣行）
        // ID Tokenはユーザー認証を表し、refresh_tokenはセッション継続のみ
        TokenData(
            accessToken = token,
            tokenType = "Bearer",
            expiresIn = EXPIRES_IN,
            refreshToken = newRefreshToken,
            idToken = null,
            scope = verified.authorizedData.scope
        )
    }

    /**
     * キャッシュ未ヒットのリフレッシュトークンが使用済みでないことを確認する
     *
     * 使用済みのjtiの再提示は、失効記録の経過時間で扱いを分ける。
     * grace期間を過ぎていれば、RFC 9700 Section 4.14.2の再利用として拒否し、どちらが正規の利用者か判別できないのでグラント全体を失効させる。
     * grace期間内であれば、応答を失ったクライアントの再試行や、再起動の後・別のサーバーでキャッシュの応答がない場合がありうるので、
     * 拒否だけしてグラントは失効させない。/oauth2/revokeが書いた記録であれば、グラントはその時点で失効済みである
     * 失効日時がgrace期間を超えて未来にある場合は、時計のずれでは説明できないので期間外として扱う
     * （読み違えた時刻やずれた時計の記録で再利用の検知が止まらないようにする）
     * 失効した時刻が記録されていない記録（revoked_at_msの追加前や、追加前のバージョンが書いたもの）は、
     * grace期間内だと示せないので期間外として扱い、grace期間の導入前と同じく再利用として扱う
     *
     * 読み取りに失敗した場合は、再利用の証拠もトークンが無効だという根拠もないので、server_errorで応答し何も失効させない
     *
     * @param verified 署名検証済みのリフレッシュトークン（ログにはクレームの一部のみ出力する）
     * @param clientId 認証済みのクライアントID
     * @param responseCache grace期間の判定に使うレスポンスキャッシュ
     * @return 未使用ならUnit、使用済みならinvalid_grant、読み取りの失敗時はserver_error
     */
    private suspend fun ensureRefreshTokenUnused(
        verified: VerifiedRefreshToken,
        clientId: String,
        responseCache: RefreshTokenResponseCache
    ): Either<TokenRotationFailure, Unit> {
        val logger = get<MineAuth>().logger
        val grantId = verified.authorizedData.grantId
        val revocation = RevokedTokenRepository.findRevokedAt(verified.tokenId).getOrElse { error ->
            logger.severe(
                "Failed to read the state of a refresh token, answered server_error: client_id=$clientId, grant_id=$grantId, error=$error"
            )
            return stateReadFailure().left()
        }

        val withinGracePeriod = when (revocation) {
            // 失効記録がなければ未使用のトークン
            TokenRevocationTime.NotRevoked -> return Unit.right()
            // 失効した時刻はエポックミリ秒で記録されているので、記録したサーバーとタイムゾーンが違っても同じ時刻として比べられる
            is TokenRevocationTime.Recorded -> responseCache.isWithinGracePeriod(revocation.revokedAt.toEpochMilli())
            // 時刻のない記録は直前のローテーションだと示せないので、grace期間外として扱う
            TokenRevocationTime.Unrecorded -> false
        }
        if (withinGracePeriod) {
            logger.warning(
                "A spent refresh token was presented again within the grace period with no cached response, denied without revoking the grant: client_id=$clientId, grant_id=$grantId"
            )
        } else {
            revokeGrantOnRefreshTokenReuse(verified, clientId)
        }
        return invalidRefreshToken().left()
    }

    /**
     * 無効なリフレッシュトークンへの応答
     * 失効・再利用・使用済みのどれであるかは応答から区別できないよう、同じ説明文を使う
     *
     * @return invalid_grant
     */
    private fun invalidRefreshToken(): TokenRotationFailure =
        TokenRotationFailure(OAuthErrorCode.INVALID_GRANT, "Invalid or expired refresh token")

    /**
     * 失効状態を読み取れなかった場合の応答
     * トークンが無効だとは言えないので、クライアントがトークンを保持したまま再試行できるserver_errorにする
     *
     * @return server_error
     */
    private fun stateReadFailure(): TokenRotationFailure =
        TokenRotationFailure(OAuthErrorCode.SERVER_ERROR, "Failed to read the state of the refresh token")

    /**
     * リフレッシュトークンの検証結果
     * 署名検証済みのデータのみを保持する（未検証JWTからのデータ混入を防止）
     */
    private data class VerifiedRefreshToken(
        val authorizedData: AuthorizedData,
        // 署名検証済みのJWT ID（トークンローテーション時の失効登録に使用）
        val tokenId: String,
        // 署名検証済みの有効期限（失効レコードのクリーンアップ用）
        val expiresAt: Date
    )

    /**
     * リフレッシュトークンを検証してデコードする
     *
     * @param refreshToken リフレッシュトークン（JWT形式）
     * @return 成功時は検証済みデータ、失敗時はnull
     */
    private fun verifyAndDecodeRefreshToken(refreshToken: String): VerifiedRefreshToken? {
        // JWT署名・有効期限・種別を検証する（リフレッシュトークン以外はVerifierが拒否する）
        val jwt = try {
            JwtProvider.refreshTokenVerifier.verify(refreshToken)
        } catch (e: JWTVerificationException) {
            // 署名無効、有効期限切れ、種別違いはinvalid_grant扱い（想定外の例外はここで握り潰さない）
            return null
        }

        // JWT ID（失効チェック・ローテーション用）
        val tokenId = jwt.id ?: return null
        val expiresAt = jwt.expiresAt ?: return null

        // 注: 失効状態の確認はここでは行わない
        // grace期間内の再提示（レスポンスキャッシュヒット）を許可するため、jtiの確認はキャッシュ未ヒット時のみ実行される
        // グラントの確認はgrace期間内でも拒否するため、呼び出し側がキャッシュの照会より先に実行する

        // クレームを取得してAuthorizedDataを構築（必須クレームの欠如はinvalid_grant扱い）
        val clientId = jwt.getClaim("client_id").asString() ?: return null
        val playerUniqueId = jwt.getClaim("playerUniqueId").asString() ?: return null
        val scope = jwt.getClaim("scope").asString() ?: return null
        val state = jwt.getClaim("state").asString() ?: return null
        val uniqueId = try {
            UUID.fromString(playerUniqueId)
        } catch (e: IllegalArgumentException) {
            return null
        }

        return VerifiedRefreshToken(
            authorizedData = AuthorizedData(
                clientId = clientId,
                redirectUri = "",
                scope = scope,
                state = state,
                codeChallenge = "",
                codeChallengeMethod = "S256",
                uniqueId = uniqueId,
                // 旧形式のトークンはgrant_idを持たない
                grantId = jwt.grantId()
            ),
            tokenId = tokenId,
            expiresAt = expiresAt
        )
    }

    /**
     * 使用済みリフレッシュトークンを失効リストに追加する
     * RFC 6749 Section 10.4: トークンローテーションにより、使用済みトークンを無効化する
     * 署名検証済みのtokenIdとexpiresAtのみを使用し、未検証JWTからのデータ混入を防止する
     *
     * 記録をこの呼び出しが作成できた場合だけ成功とし、呼び出し側は失敗時にトークン発行を中止する
     * （旧トークンが有効なまま、または使用済みのトークンから新トークンを発行するとローテーションの安全性が崩れるため）
     * - 既に記録があれば、状態を読み取った後に並行したローテーション（他のサーバーを含む）か/oauth2/revokeが
     *   使用済みにしたトークンなので、invalid_grantを返す。チェーンを分岐させないため、再利用とは扱わずグラントも失効させない
     * - 書き込みに失敗した場合はserver_errorを返す。記録は作られていないので、クライアントは同じトークンで再試行できる
     *
     * コルーチンのキャンセルと想定外の例外は捕捉せずに送出する（成功にも失敗の応答にも見せかけない）
     *
     * @param verified 署名検証済みのリフレッシュトークン
     * @param clientId クライアントID
     * @param responseCache 失効日時の記録に使う時計を持つレスポンスキャッシュ
     * @return 記録を作成した場合はUnit、それ以外は応答すべきエラー
     */
    private suspend fun revokeOldRefreshToken(
        verified: VerifiedRefreshToken,
        clientId: String,
        responseCache: RefreshTokenResponseCache
    ): Either<TokenRotationFailure, Unit> {
        // JWTの有効期限をLocalDateTimeに変換してDBに保存
        val expiresAt = LocalDateTime.ofInstant(verified.expiresAt.toInstant(), ZoneId.systemDefault())
        // 再提示の際にgrace期間内かを同じ時計で判定できるよう、失効日時はレスポンスキャッシュの時計で記録する
        // （リポジトリが判定用にエポックミリ秒で保存する）
        val revokedAt = Instant.ofEpochMilli(responseCache.now())

        return RevokedTokenRepository.revokeIfAbsent(
            verified.tokenId, TokenType.REFRESH_TOKEN, clientId, expiresAt, revokedAt
        ).mapLeft { error ->
            val logger = get<MineAuth>().logger
            val grantId = verified.authorizedData.grantId
            when (error) {
                RevokedTokenError.AlreadyRevoked -> {
                    logger.warning(
                        "A refresh token was spent by another request during its rotation, denied: client_id=$clientId, grant_id=$grantId"
                    )
                    invalidRefreshToken()
                }
                is RevokedTokenError.DatabaseError -> {
                    logger.severe("Failed to revoke the old refresh token: client_id=$clientId, grant_id=$grantId, error=$error")
                    TokenRotationFailure(OAuthErrorCode.SERVER_ERROR, "Failed to rotate refresh token")
                }
            }
        }
    }

    /**
     * access_tokenからat_hashを計算する
     * OIDC Core Section 3.1.3.6 準拠
     * SHA-256でハッシュし、左半分（128ビット）をBase64URLエンコード
     */
    private fun calculateAtHash(accessToken: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(accessToken.toByteArray(Charsets.US_ASCII))
        val leftHalf = hash.copyOf(16) // 左128ビット
        return Base64.getUrlEncoder().withoutPadding().encodeToString(leftHalf)
    }

    /**
     * OpenID Connect ID Tokenを発行する
     * OIDC Core Section 2, 3.1.3.7 準拠
     *
     * @param data 認可データ
     * @param clientId クライアントID
     * @param accessToken アクセストークン（at_hash計算用）
     * @return ID Token（JWT形式）
     */
    private suspend fun issueIdToken(
        data: AuthorizedData,
        clientId: String?,
        accessToken: String
    ): String {
        val configData = get<JWTConfigData>()
        val config = get<MineAuthConfig>()
        val now = Date()
        val sub = data.uniqueId.toString()

        // スコープ解析（UserInfoResponseと同じクレームを返すため）
        val scopes = data.scope.split(" ").filter { it.isNotBlank() }
        val hasProfileScope = scopes.contains("profile")
        val hasEmailScope = scopes.contains("email")
        val hasRolesScope = scopes.contains("roles")

        // プレイヤー名を取得（profileまたはemailスコープで必要）
        // /userinfoエンドポイントと同様にスコープ横断で利用する
        val needsPlayerName = hasProfileScope || hasEmailScope
        val playerName = if (needsPlayerName) {
            org.bukkit.Bukkit.getOfflinePlayer(data.uniqueId).name ?: "Unknown"
        } else null

        // emailFormatが設定されている場合、メールアドレスを生成
        val email = if (hasEmailScope && playerName != null) {
            config.server.emailFormat?.let { format ->
                UserInfoResponse.generateEmail(format, sub, playerName)
            }
        } else null

        // rolesスコープがリクエストされている場合、LuckPermsからグループを取得
        val roles = if (hasRolesScope && LuckPermsIntegration.available) {
            LuckPermsIntegration.getPlayerGroups(data.uniqueId)
        } else null

        // typはRPとの互換性のためJWTのままとし、token_typeクレームは持たせない
        return JwtProvider.newToken(TokenKind.ID)
            // JWTヘッダー
            .withKeyId(configData.keyId.toString())
            // 必須クレーム（OIDC Core Section 2）
            .withIssuer(configData.issuer)                           // iss: Issuer Identifier
            .withSubject(sub)                                        // sub: Subject Identifier
            .withAudience(clientId ?: data.clientId)                 // aud: Audience
            .withExpiresAt(Date(now.time + 3600 * 1000))             // exp: 1時間後
            .withIssuedAt(now)                                        // iat: 発行時刻
            // 条件付きクレーム
            .withClaim("auth_time", data.authTime / 1000)            // auth_time: 認証時刻（秒単位）
            .apply {
                // nonceが存在する場合のみ含める（リプレイ攻撃防止用）
                data.nonce?.let { withClaim("nonce", it) }

                // profileスコープ: name, picture, preferred_username
                if (hasProfileScope && playerName != null) {
                    withClaim("name", playerName)
                    withClaim("preferred_username", playerName)
                    withClaim("picture", UserInfoResponse.generatePicture(config.server.pictureFormat, sub, playerName))
                }

                // emailスコープ: email, email_verified
                if (email != null) {
                    withClaim("email", email)
                    withClaim("email_verified", false)
                }

                // rolesスコープ: roles（LuckPermsグループ）
                if (roles != null) {
                    withClaim("roles", roles)
                }
            }
            // 任意クレーム（Authorization Code Flowでは任意だが、検証に有用）
            .withClaim("at_hash", calculateAtHash(accessToken))      // at_hash: Access Token hash
            // 署名（RS256）— JwtProviderのキャッシュ済みアルゴリズムを使用
            .sign(JwtProvider.algorithm)
    }
}
