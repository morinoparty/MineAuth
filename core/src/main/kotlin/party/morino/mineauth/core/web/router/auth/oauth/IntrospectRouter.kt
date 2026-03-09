package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.authenticateConfidentialClient
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractClientCredentials

/**
 * RFC 7662 OAuth 2.0 Token Introspection エンドポイント
 *
 * リソースサーバーがトークンの有効性とメタデータを問い合わせるためのエンドポイント
 * https://datatracker.ietf.org/doc/html/rfc7662
 */
object IntrospectRouter : KoinComponent {
    private val plugin: MineAuth by inject()

    /**
     * POST /oauth2/introspect エンドポイントを登録する
     *
     * RFC 7662に基づくトークンイントロスペクションエンドポイント
     * - クライアント認証が必須（Confidentialクライアント）
     * - 有効なトークンの場合: メタデータ付きで active=true を返す
     * - 無効/期限切れ/失効済みトークンの場合: active=false のみを返す
     */
    fun Route.introspectRouter() {
        post("/introspect") {
            val formParameters = call.receiveParameters()

            // RFC 7662 Section 2.1: 必須パラメータ token
            val token = formParameters["token"]
            if (token == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: token")
                return@post
            }

            // クライアント認証（共通ユーティリティを使用）
            val credentials = when (val result = extractClientCredentials(formParameters)) {
                is Either.Left -> {
                    call.respondOAuthError(result.value.errorCode, result.value.message)
                    return@post
                }
                is Either.Right -> result.value
            }
            when (val result = authenticateConfidentialClient(credentials)) {
                is Either.Left -> {
                    call.respondOAuthError(result.value.errorCode, result.value.message)
                    return@post
                }
                is Either.Right -> { /* 認証成功 */ }
            }

            // トークンを検証してイントロスペクションレスポンスを生成
            val response = introspectToken(token)

            // RFC 7662 Section 2.2: 常に200 OKで返す
            call.respond(HttpStatusCode.OK, response)
        }
    }

    /**
     * トークンを検証してイントロスペクションレスポンスを生成する
     *
     * @param token 検証するトークン（JWT形式）
     * @return active=true（メタデータ付き）または active=false
     */
    private suspend fun introspectToken(token: String): IntrospectionResponse {
        return try {
            // 署名・有効期限を検証（JwtProviderのキャッシュ済みverifierを使用）
            val jwt = try {
                JwtProvider.verifier.verify(token)
            } catch (e: TokenExpiredException) {
                // 署名は有効だが期限切れ → inactive
                return IntrospectionResponse(active = false)
            } catch (e: JWTVerificationException) {
                // 署名不正・issuer不一致 → inactive
                return IntrospectionResponse(active = false)
            }

            // JWT IDによる失効チェック
            val tokenId = jwt.id
            if (tokenId != null && RevokedTokenRepository.isRevoked(tokenId)) {
                return IntrospectionResponse(active = false)
            }

            // トークンの種別判定（claim "token_type": "token" = アクセストークン）
            val tokenTypeClaim = jwt.getClaim("token_type").asString()
            val responseTokenType = if (tokenTypeClaim == "refresh_token") null else "Bearer"

            // プレイヤーUUIDを取得（usernameフィールドに使用）
            val playerUniqueId = jwt.getClaim("playerUniqueId").asString()
            val username = playerUniqueId?.let {
                try {
                    org.bukkit.Bukkit.getOfflinePlayer(java.util.UUID.fromString(it)).name
                } catch (e: Exception) {
                    null
                }
            }

            IntrospectionResponse(
                active = true,
                scope = jwt.getClaim("scope").asString(),
                clientId = jwt.getClaim("client_id").asString(),
                username = username,
                tokenType = responseTokenType,
                exp = jwt.expiresAt?.time?.div(1000),
                iat = jwt.issuedAt?.time?.div(1000),
                nbf = jwt.notBefore?.time?.div(1000),
                sub = playerUniqueId,
                aud = jwt.audience?.firstOrNull(),
                iss = jwt.issuer,
                jti = jwt.id
            )
        } catch (e: Exception) {
            plugin.logger.warning("Token introspection error: ${e.message}")
            IntrospectionResponse(active = false)
        }
    }
}
