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
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.components.auth.ClientData
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractBasicCredentials
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * RFC 7009 OAuth 2.0 Token Revocation エンドポイント
 *
 * クライアントがアクセストークンまたはリフレッシュトークンを失効させるためのエンドポイント
 * https://datatracker.ietf.org/doc/html/rfc7009
 */
object RevokeRouter : KoinComponent {
    private val plugin: MineAuth by inject()

    /**
     * POST /oauth2/revoke エンドポイントを登録する
     *
     * RFC 7009に基づくトークン失効エンドポイント
     * - クライアント認証が必須（Confidentialクライアント）
     * - 成功時は200 OKを返す（トークンの有効性に関わらず）
     * - 情報漏洩防止のため、トークンが存在しない場合も200を返す
     */
    fun Route.revokeRouter() {
        post("/revoke") {
            val formParameters = call.receiveParameters()

            // 必須パラメータ: token
            val token = formParameters["token"]
            if (token == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: token")
                return@post
            }

            // オプションパラメータ: token_type_hint（"access_token" または "refresh_token"）
            val tokenTypeHint = formParameters["token_type_hint"]

            // クライアント認証（RFC 7009 Section 2.1）
            // client_secret_basic (Authorization: Basic) または client_secret_post (ボディ) をサポート
            val basicCredentials = extractBasicCredentials()

            // RFC 6749 Section 2.3: 認証方式の併用を禁止
            val hasBodyCredentials = formParameters["client_id"] != null || formParameters["client_secret"] != null
            if (basicCredentials != null && hasBodyCredentials) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Multiple client authentication methods are not allowed")
                return@post
            }

            val clientId = formParameters["client_id"] ?: basicCredentials?.first
            val clientSecret = (formParameters["client_secret"] ?: basicCredentials?.second)?.ifEmpty { null }

            if (clientId == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: client_id")
                return@post
            }

            if (clientSecret == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: client_secret")
                return@post
            }

            // クライアント検証
            val clientData = try {
                ClientData.getClientData(clientId)
            } catch (e: Exception) {
                plugin.logger.warning("Client not found for revocation: $clientId")
                call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client not found")
                return@post
            }

            // Confidentialクライアントのみ許可
            if (clientData !is ClientData.ConfidentialClientData) {
                plugin.logger.warning("Non-confidential client attempted revocation: $clientId")
                call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client type mismatch")
                return@post
            }

            // クライアントシークレットの検証（Argon2idによる定数時間比較）
            if (!clientData.verifySecret(clientSecret)) {
                plugin.logger.warning("Client authentication failed for revocation: $clientId")
                call.respondOAuthError(OAuthErrorCode.INVALID_CLIENT, "Client authentication failed")
                return@post
            }

            // トークンの検証と失効処理
            val revocationResult = revokeToken(token, tokenTypeHint, clientId)

            // RFC 7009 Section 2.2:
            // 成功時は200 OKを返す（トークンの有効性に関わらず）
            // これは情報漏洩を防ぐため
            if (revocationResult) {
                plugin.logger.info("Token revoked successfully for client: $clientId")
            } else {
                // トークンが無効、既に失効済み、または他のクライアントのトークンの場合
                // セキュリティ上、成功として扱う（情報漏洩防止）
                plugin.logger.info("Token revocation completed (token may not exist or already revoked): $clientId")
            }

            call.respond(HttpStatusCode.OK)
        }
    }

    /**
     * トークンを失効させる
     *
     * @param token 失効させるトークン（JWT形式）
     * @param tokenTypeHint トークン種別のヒント（オプション）
     * @param clientId リクエスト元のクライアントID
     * @return 失効処理が成功した場合true
     */
    private suspend fun revokeToken(
        token: String,
        tokenTypeHint: String?,
        clientId: String
    ): Boolean {
        return try {
            // JWT署名と形式を検証
            val algorithm = Algorithm.RSA256(
                getKeys().second as RSAPublicKey,
                getKeys().first as RSAPrivateKey
            )
            val verifier = JWT.require(algorithm)
                .withIssuer(get<JWTConfigData>().issuer)
                .build()

            // 署名検証（有効期限は無視 - 期限切れトークンも失効可能）
            val jwt = try {
                verifier.verify(token)
            } catch (e: Exception) {
                // 署名が無効なトークンは失効対象外だが、成功として扱う
                return true
            }

            // JWT IDの取得
            val tokenId = jwt.id
            if (tokenId == null) {
                // JWT IDがないトークンは失効対象外
                return true
            }

            // トークンのclient_idを確認（自分のクライアントのトークンのみ失効可能）
            val tokenClientId = jwt.getClaim("client_id").asString()
            if (tokenClientId != clientId) {
                // 他のクライアントのトークンは失効できないが、成功として扱う（情報漏洩防止）
                plugin.logger.warning("Attempted to revoke token of different client: requested=$clientId, token=$tokenClientId")
                return true
            }

            // トークン種別の判定
            val tokenTypeFromClaim = jwt.getClaim("token_type").asString()
            val tokenType = when {
                tokenTypeFromClaim == "refresh_token" -> TokenType.REFRESH_TOKEN
                tokenTypeFromClaim == "token" -> TokenType.ACCESS_TOKEN
                tokenTypeHint != null -> TokenType.fromHint(tokenTypeHint) ?: TokenType.ACCESS_TOKEN
                else -> TokenType.ACCESS_TOKEN
            }

            // トークンの有効期限を取得
            val expiresAt = jwt.expiresAt?.let {
                LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), ZoneId.systemDefault())
            } ?: LocalDateTime.now().plusDays(30)

            // 失効トークンとして登録
            val result = RevokedTokenRepository.revoke(tokenId, tokenType, clientId, expiresAt)

            result.isRight()
        } catch (e: Exception) {
            plugin.logger.warning("Token revocation error: ${e.message}")
            // エラーが発生しても成功として扱う（情報漏洩防止）
            true
        }
    }
}
