package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import com.auth0.jwt.JWT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import party.morino.mineauth.core.web.components.auth.TokenData
import party.morino.mineauth.core.web.components.auth.UserInfoResponse
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.authorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.authenticateClient
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractClientCredentials
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.validateCodeVerifier
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenType
import java.security.MessageDigest
import java.util.*

object TokenRouter: KoinComponent {
    val plugin: MineAuth by inject()
    private const val EXPIRES_IN = 300
    // RFC 6749 Section 4.1.2: 認可コードの最大有効期間（10分推奨）
    private const val AUTHORIZATION_CODE_LIFETIME_MS = 10 * 60 * 1000L

    fun Route.tokenRouter() {
        post("/token") {
            val formParameters = call.receiveParameters()
            val grantType = formParameters["grant_type"]

            // RFC 6749 Section 5.2: grant_type欠如はinvalid_request
            if (grantType == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: grant_type")
                return@post
            }

            // クレデンシャル抽出（Basic認証 + フォームパラメータ、併用禁止チェック含む）
            val credentials = when (val result = extractClientCredentials(formParameters)) {
                is Either.Left -> {
                    call.respondOAuthError(result.value.errorCode, result.value.message)
                    return@post
                }
                is Either.Right -> result.value
            }

            if (grantType == "refresh_token") {
                //refresh_tokenの処理 https://tools.ietf.org/html/rfc6749#section-6
                // クライアント認証（Confidential/Public両対応）
                val clientData = when (val result = authenticateClient(credentials)) {
                    is Either.Left -> {
                        call.respondOAuthError(result.value.errorCode, result.value.message)
                        return@post
                    }
                    is Either.Right -> result.value
                }
                val clientId = clientData.clientId

                val refreshToken = formParameters["refresh_token"]
                if (refreshToken == null) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: refresh_token")
                    return@post
                }

                // リフレッシュトークンの検証（署名・有効期限・token_type）
                val verified = verifyAndDecodeRefreshToken(refreshToken)
                if (verified == null) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid or expired refresh token")
                    return@post
                }

                // クライアントIDの一致を確認
                if (verified.authorizedData.clientId != clientId) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Refresh token was not issued to this client")
                    return@post
                }

                // リフレッシュトークン内のスコープを再検証（許可リスト変更に対応）
                if (!OAuthScope.isValid(verified.authorizedData.scope)) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_SCOPE, "Refresh token contains invalid scope(s)")
                    return@post
                }

                // RFC 6749 Section 10.4: リフレッシュトークンローテーション
                // 旧トークンの失効を先に行い、失敗時はトークン発行を中止する
                if (!revokeOldRefreshToken(verified.tokenId, verified.expiresAt, clientId)) {
                    call.respondOAuthError(OAuthErrorCode.SERVER_ERROR, "Failed to rotate refresh token")
                    return@post
                }
                val token = issueToken(verified.authorizedData, clientId)
                val newRefreshToken = issueRefreshToken(verified.authorizedData, clientId)

                // refresh_token使用時はID Tokenを発行しない（OIDC一般慣行）
                // ID Tokenはユーザー認証を表し、refresh_tokenはセッション継続のみ
                // RFC 6749 Section 5.1: トークンレスポンスにキャッシュ禁止ヘッダーを付与
                call.response.header(HttpHeaders.CacheControl, "no-store")
                call.response.header(HttpHeaders.Pragma, "no-cache")
                call.respond(HttpStatusCode.OK, TokenData(
                    accessToken = token,
                    tokenType = "Bearer",
                    expiresIn = EXPIRES_IN,
                    refreshToken = newRefreshToken,
                    idToken = null,
                    scope = verified.authorizedData.scope
                ))
            }else if (grantType == "authorization_code") {
                //authorization_codeの処理 https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.3
                // 期限切れ認可コードの遅延クリーンアップ（リクエスト契機で実行）
                OAuthRouter.cleanupExpiredCodes(AUTHORIZATION_CODE_LIFETIME_MS)
                val code = formParameters["code"]
                val redirectUri = formParameters["redirect_uri"]
                val codeVerifier = formParameters["code_verifier"]
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
                    return@post
                }

                // クライアント認証（Confidential/Public両対応）
                val clientData = when (val result = authenticateClient(credentials)) {
                    is Either.Left -> {
                        call.respondOAuthError(result.value.errorCode, result.value.message)
                        return@post
                    }
                    is Either.Right -> result.value
                }

                // ConcurrentHashMap.removeで取得と削除を原子的に行い、並行リクエストによる二重使用を防止
                val data = authorizedData.remove(code) ?: run {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The authorization code is invalid or expired")
                    return@post
                }

                // RFC 6749 Section 4.1.2: 認可コードの有効期限チェック（最大10分）
                val codeAge = System.currentTimeMillis() - data.authTime
                if (codeAge > AUTHORIZATION_CODE_LIFETIME_MS) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The authorization code has expired")
                    return@post
                }

                // nullチェック済みのためnon-null変数に再代入
                val validRedirectUri = redirectUri!!
                val validCodeVerifier = codeVerifier!!

                if (data.clientId != clientId || data.redirectUri != validRedirectUri) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid client_id or redirect_uri")
                    return@post
                }

                if (!validateCodeVerifier(data.codeChallenge, validCodeVerifier)) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The code_verifier is invalid")
                    return@post
                }

                val token = issueToken(data, clientId)
                val refreshToken = issueRefreshToken(data, clientId)
                // scopeに"openid"が含まれる場合のみID Tokenを発行（OIDC準拠）
                val idToken = if (data.scope.contains("openid")) {
                    issueIdToken(data, clientId, token)
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

    private fun issueToken(
        data: AuthorizedData, clientId: String?
    ): String = JWT.create().withIssuer(get<JWTConfigData>().issuer)
        .withAudience(data.clientId)
        .withNotBefore(Date(System.currentTimeMillis()))
        .withExpiresAt(Date(System.currentTimeMillis() + EXPIRES_IN * 1_000.toLong()))
        .withIssuedAt(Date(System.currentTimeMillis()))
        .withJWTId(UUID.randomUUID().toString())
        .withClaim("client_id", clientId).withClaim("playerUniqueId", data.uniqueId.toString()).withClaim("scope", data.scope).withClaim("state", data.state).withClaim("scope", data.scope)
        .withClaim("token_type", "token").sign(JwtProvider.algorithm)

    private fun issueRefreshToken(
        data: AuthorizedData, clientId: String?
    ): String = JWT.create().withIssuer(get<JWTConfigData>().issuer)
        .withAudience(data.clientId)
        .withNotBefore(Date(System.currentTimeMillis()))
        .withExpiresAt(Date(System.currentTimeMillis() + (3_600_000.toLong() * 24 * 30)))
        .withIssuedAt(Date(System.currentTimeMillis())).withJWTId(UUID.randomUUID().toString())
        .withClaim("client_id", clientId)
        .withClaim("playerUniqueId", data.uniqueId.toString())
        .withClaim("scope", data.scope)
        .withClaim("state", data.state)
        .withClaim("scope", data.scope)
        .withClaim("token_type", "refresh_token").sign(JwtProvider.algorithm)

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
        return try {
            // JWT署名と有効期限を検証（JwtProviderのキャッシュ済みverifierを使用）
            val jwt = JwtProvider.verifier.verify(refreshToken)

            // token_typeがrefresh_tokenであることを確認
            val tokenType = jwt.getClaim("token_type").asString()
            if (tokenType != "refresh_token") {
                return null
            }

            // JWT ID（失効チェック・ローテーション用）
            val tokenId = jwt.id ?: return null
            val expiresAt = jwt.expiresAt ?: return null

            // RFC 7009: トークンが失効済みかチェック
            if (RevokedTokenRepository.isRevokedBlocking(tokenId)) {
                return null
            }

            // クレームを取得してAuthorizedDataを構築（必須クレームの欠如はinvalid_grant扱い）
            val clientId = jwt.getClaim("client_id").asString() ?: return null
            val playerUniqueId = jwt.getClaim("playerUniqueId").asString() ?: return null
            val scope = jwt.getClaim("scope").asString() ?: return null
            val state = jwt.getClaim("state").asString() ?: return null

            VerifiedRefreshToken(
                authorizedData = AuthorizedData(
                    clientId = clientId,
                    redirectUri = "",
                    scope = scope,
                    state = state,
                    codeChallenge = "",
                    codeChallengeMethod = "S256",
                    uniqueId = UUID.fromString(playerUniqueId)
                ),
                tokenId = tokenId,
                expiresAt = expiresAt
            )
        } catch (e: Exception) {
            // 署名無効、有効期限切れ、その他のJWTエラー
            null
        }
    }

    /**
     * 使用済みリフレッシュトークンを失効リストに追加する
     * RFC 6749 Section 10.4: トークンローテーションにより、使用済みトークンを無効化する
     * 署名検証済みのtokenIdとexpiresAtのみを使用し、未検証JWTからのデータ混入を防止する
     *
     * 失効登録に失敗した場合はfalseを返し、呼び出し側でトークン発行を中止する
     * （旧トークンが有効なまま新トークンを発行するとローテーションの安全性が崩れるため）
     *
     * @param tokenId 署名検証済みのJWT ID
     * @param expiresAt 署名検証済みの有効期限
     * @param clientId クライアントID
     * @return 失効登録が成功した場合true
     */
    private suspend fun revokeOldRefreshToken(tokenId: String, expiresAt: Date, clientId: String): Boolean {
        return try {
            // JWTの有効期限をLocalDateTimeに変換してDBに保存
            val expiresAtLocal = java.time.LocalDateTime.ofInstant(
                expiresAt.toInstant(), java.time.ZoneId.systemDefault()
            )
            val result = RevokedTokenRepository.revoke(tokenId, TokenType.REFRESH_TOKEN, clientId, expiresAtLocal)
            result.isRight()
        } catch (e: Exception) {
            plugin.logger.warning("Failed to revoke old refresh token: ${e.message}")
            false
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

        return JWT.create()
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
                    withClaim("picture", "${UserInfoResponse.AVATAR_BASE_URL}$sub")
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
