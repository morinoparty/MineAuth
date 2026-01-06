package party.morino.mineauth.core.web.router.auth.oauth

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.utils.KeyUtils.getKeys
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.components.auth.TokenData
import party.morino.mineauth.core.web.router.auth.data.AuthorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouter.authorizedData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.validateCodeVerifier
import party.morino.mineauth.core.web.router.auth.oauth.OAuthErrorCode
import party.morino.mineauth.core.web.router.auth.oauth.respondOAuthError
import java.security.MessageDigest
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.*

object TokenRouter: KoinComponent {
    val plugin: MineAuth by inject()
    private const val EXPIRES_IN = 300
    fun Route.tokenRouter() {
        post("/token") {
            val formParameters = call.receiveParameters()
            val grantType = formParameters["grant_type"]
            if (grantType == "refresh_token") {
                //refresh_tokenの処理 https://tools.ietf.org/html/rfc6749#section-6
                val clientId = formParameters["client_id"]
                val clientSecret = formParameters["client_secret"]
                if (clientId == null) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: client_id")
                    return@post
                }

                if(clientSecret != null){
                    // Confidentialクライアントの場合
                    val refreshToken = formParameters["refresh_token"]
                    if (refreshToken == null) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: refresh_token")
                        return@post
                    }

                    // クライアントデータの取得と型チェック
                    val clientData = ClientData.getClientData(clientId)
                    if (clientData !is ClientData.ConfidentialClientData) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client type mismatch")
                        return@post
                    }
                    // 定数時間比較でタイミング攻撃を防止
                    if (!MessageDigest.isEqual(
                            clientData.hashedClientSecret.toByteArray(Charsets.UTF_8),
                            clientSecret.toByteArray(Charsets.UTF_8)
                        )) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client authentication failed")
                        return@post
                    }

                    // リフレッシュトークンの検証（署名・有効期限・token_type）
                    val authorizedData = verifyAndDecodeRefreshToken(refreshToken)
                    if (authorizedData == null) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid or expired refresh token")
                        return@post
                    }

                    // クライアントIDの一致を確認
                    if (authorizedData.clientId != clientId) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Refresh token was not issued to this client")
                        return@post
                    }

                    val token = issueToken(authorizedData, clientId)
                    // refresh_token使用時はID Tokenを発行しない（OIDC一般慣行）
                    // ID Tokenはユーザー認証を表し、refresh_tokenはセッション継続のみ
                    call.respond(HttpStatusCode.OK, TokenData(
                        accessToken = token,
                        tokenType = "Bearer",
                        expiresIn = EXPIRES_IN,
                        refreshToken = refreshToken,
                        idToken = null
                    ))

                }else{
                    // Publicクライアントの場合
                    // RFC 6749 Section 6: refresh_tokenリクエストにredirect_uriは必須ではない
                    val refreshToken = formParameters["refresh_token"]
                    if (refreshToken == null) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: refresh_token")
                        return@post
                    }

                    // リフレッシュトークンの検証（署名・有効期限・token_type）
                    val authorizedData = verifyAndDecodeRefreshToken(refreshToken)
                    if (authorizedData == null) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid or expired refresh token")
                        return@post
                    }

                    // クライアントIDの一致を確認
                    if (authorizedData.clientId != clientId) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Refresh token was not issued to this client")
                        return@post
                    }

                    val token = issueToken(authorizedData, clientId)
                    // refresh_token使用時はID Tokenを発行しない（OIDC一般慣行）
                    call.respond(HttpStatusCode.OK, TokenData(
                        accessToken = token,
                        tokenType = "Bearer",
                        expiresIn = EXPIRES_IN,
                        refreshToken = refreshToken,
                        idToken = null
                    ))
                }
            }else if (grantType == "authorization_code") {
                //authorization_codeの処理 https://datatracker.ietf.org/doc/html/rfc6749#section-4.1.3
                val code = formParameters["code"]
                val redirectUri = formParameters["redirect_uri"]
                val clientId = formParameters["client_id"]
                val codeVerifier = formParameters["code_verifier"]

                val clientSecret = formParameters["client_secret"] //client_secret_post
                val data = authorizedData[code] ?: run {
                    authorizedData.remove(code)
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The authorization code is invalid or expired")
                    return@post
                }
                authorizedData.remove(code)

                if (code == null || redirectUri == null || clientId == null || codeVerifier == null) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameters")
                    return@post
                }
                if(clientSecret != null){
                    // Confidentialクライアントの場合：クライアント認証を実行
                    val clientData = ClientData.getClientData(clientId)
                    if (clientData !is ClientData.ConfidentialClientData) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client type mismatch")
                        return@post
                    }
                    // 定数時間比較でタイミング攻撃を防止
                    if (!MessageDigest.isEqual(
                            clientData.hashedClientSecret.toByteArray(Charsets.UTF_8),
                            clientSecret.toByteArray(Charsets.UTF_8)
                        )) {
                        call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client authentication failed")
                        return@post
                    }
                }

                if (data.clientId != clientId || data.redirectUri != redirectUri) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "Invalid client_id or redirect_uri")
                    return@post
                }

                if (!validateCodeVerifier(data.codeChallenge, codeVerifier)) {
                    call.respondOAuthError(OAuthErrorCode.INVALID_GRANT, "The code_verifier is invalid")
                    return@post
                }

                val token = issueToken(data, clientId)
                val refreshToken = issueRefreshToken(data, clientId)
                // scopeに"openid"が含まれる場合のみID Tokenを発行（OIDC準拠）
                val idToken = if (data.scope.contains("openid")) {
                    issueIdToken(data, clientId, token)
                } else null

                call.respond(
                    HttpStatusCode.OK, TokenData(
                        accessToken = token,
                        tokenType = "Bearer",
                        expiresIn = EXPIRES_IN,
                        refreshToken = refreshToken,
                        idToken = idToken
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
        .withClaim("token_type", "token").sign(
            Algorithm.RSA256(
                getKeys().second as RSAPublicKey, getKeys().first as RSAPrivateKey
            )
        )

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
        .withClaim("token_type", "refresh_token").sign(
            Algorithm.RSA256(
                getKeys().second as RSAPublicKey, getKeys().first as RSAPrivateKey
            )
        )

    /**
     * リフレッシュトークンからアクセストークンを再発行する
     *
     * @param refreshToken リフレッシュトークン（JWT形式）
     * @return 成功時は新しいアクセストークン、失敗時はnull
     */
    private fun verifyAndDecodeRefreshToken(refreshToken: String): AuthorizedData? {
        return try {
            // JWT署名と有効期限を検証（RFC 6749準拠のセキュリティ要件）
            val algorithm = Algorithm.RSA256(
                getKeys().second as RSAPublicKey,
                getKeys().first as RSAPrivateKey
            )
            val verifier = JWT.require(algorithm)
                .withIssuer(get<JWTConfigData>().issuer)
                .build()

            // 署名検証・有効期限検証を実行（無効な場合は例外がスローされる）
            val jwt = verifier.verify(refreshToken)

            // token_typeがrefresh_tokenであることを確認
            val tokenType = jwt.getClaim("token_type").asString()
            if (tokenType != "refresh_token") {
                return null
            }

            // クレームを取得してAuthorizedDataを構築
            val clientId = jwt.getClaim("client_id").asString()
            val playerUniqueId = jwt.getClaim("playerUniqueId").asString()
            val scope = jwt.getClaim("scope").asString()
            val state = jwt.getClaim("state").asString()

            AuthorizedData(
                clientId = clientId,
                redirectUri = "",
                scope = scope,
                state = state,
                codeChallenge = "",
                codeChallengeMethod = "S256",
                uniqueId = UUID.fromString(playerUniqueId)
            )
        } catch (e: Exception) {
            // 署名無効、有効期限切れ、その他のJWTエラー
            null
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
    private fun issueIdToken(
        data: AuthorizedData,
        clientId: String?,
        accessToken: String
    ): String {
        val configData = get<JWTConfigData>()
        val now = Date()

        return JWT.create()
            // JWTヘッダー
            .withKeyId(configData.keyId.toString())
            // 必須クレーム（OIDC Core Section 2）
            .withIssuer(configData.issuer)                           // iss: Issuer Identifier
            .withSubject(data.uniqueId.toString())                   // sub: Subject Identifier
            .withAudience(clientId ?: data.clientId)                 // aud: Audience
            .withExpiresAt(Date(now.time + 3600 * 1000))             // exp: 1時間後
            .withIssuedAt(now)                                        // iat: 発行時刻
            // 条件付きクレーム
            .withClaim("auth_time", data.authTime / 1000)            // auth_time: 認証時刻（秒単位）
            .apply {
                // nonceが存在する場合のみ含める（リプレイ攻撃防止用）
                data.nonce?.let { withClaim("nonce", it) }
            }
            // 任意クレーム（Authorization Code Flowでは任意だが、検証に有用）
            .withClaim("at_hash", calculateAtHash(accessToken))      // at_hash: Access Token hash
            // 署名（RS256）
            .sign(
                Algorithm.RSA256(
                    getKeys().second as RSAPublicKey,
                    getKeys().first as RSAPrivateKey
                )
            )
    }
}