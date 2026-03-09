package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.coroutines.cancellation.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.authenticateConfidentialClient
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractClientCredentials
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

            // クライアント認証（共通ユーティリティを使用）
            val credentials = when (val result = extractClientCredentials(formParameters)) {
                is Either.Left -> {
                    call.respondOAuthError(result.value.errorCode, result.value.message)
                    return@post
                }
                is Either.Right -> result.value
            }
            val clientData = when (val result = authenticateConfidentialClient(credentials)) {
                is Either.Left -> {
                    call.respondOAuthError(result.value.errorCode, result.value.message)
                    return@post
                }
                is Either.Right -> result.value
            }

            // トークンの検証と失効処理
            val revocationResult = revokeToken(token, tokenTypeHint, clientData.clientId)

            // RFC 7009 Section 2.2:
            // 成功時は200 OKを返す（トークンの有効性に関わらず）
            // これは情報漏洩を防ぐため
            if (revocationResult) {
                plugin.logger.info("Token revoked successfully for client: ${clientData.clientId}")
            } else {
                // トークンが無効、既に失効済み、または他のクライアントのトークンの場合
                // セキュリティ上、成功として扱う（情報漏洩防止）
                plugin.logger.info("Token revocation completed (token may not exist or already revoked): ${clientData.clientId}")
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
            // lenientVerifierで署名検証を行い、期限切れトークンも許容する
            // JWTVerificationException: 署名不正等 → 成功として扱う（情報漏洩防止）
            val jwt: DecodedJWT = try {
                JwtProvider.lenientVerifier.verify(token)
            } catch (e: JWTVerificationException) {
                // 署名不正・issuer不一致などは失効対象外だが、成功として扱う
                return true
            }

            // 失効処理に必要なクレームを抽出して登録する
            registerRevocation(jwt, tokenTypeHint, clientId)
        } catch (e: CancellationException) {
            // コルーチンのキャンセルは再送出する（握り潰してはいけない）
            throw e
        } catch (e: Exception) {
            plugin.logger.warning("Token revocation error: ${e.message}")
            // 内部エラーは成功として扱う（情報漏洩防止）
            true
        }
    }

    /**
     * 検証済みJWTからクレームを抽出し、失効トークンとして登録する
     *
     * @param jwt 検証済み（または期限切れ）のJWT
     * @param tokenTypeHint トークン種別のヒント
     * @param clientId リクエスト元のクライアントID
     * @return 登録が成功した場合true
     */
    private suspend fun registerRevocation(
        jwt: DecodedJWT,
        tokenTypeHint: String?,
        clientId: String
    ): Boolean {
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

        // トークン種別の判定（claim値 "token" はアクセストークンを示す）
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

        return result.isRight()
    }
}
