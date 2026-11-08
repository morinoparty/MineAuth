package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlin.coroutines.cancellation.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.model.tokenKind
import party.morino.mineauth.core.repository.RevokedTokenRepository
import party.morino.mineauth.core.repository.TokenType
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import party.morino.mineauth.core.web.ratelimit.clientIpOf
import party.morino.mineauth.core.web.ratelimit.respondClientAuthFailure
import party.morino.mineauth.core.web.ratelimit.respondRateLimited
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.authenticateConfidentialClient
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractClientCredentials
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.model.grantId
import party.morino.mineauth.core.repository.RevokedGrantRepository
import party.morino.mineauth.core.web.telemetry.withSpan
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.logging.Level

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
     * - 失効を記録できなかった場合はserver_errorを返す（失効済みと誤認させない）
     */
    fun Route.revokeRouter() {
        post("/revoke") {
            val formParameters = call.receiveParameters()

            // 必須パラメータ: token（空値は省略扱い、重複はinvalid_request）
            val token = formParameters.readSingleParameter("token").getOrElse { failure ->
                call.respondOAuthError(failure.errorCode, failure.message)
                return@post
            }
            if (token == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: token")
                return@post
            }

            // オプションパラメータ: token_type_hint（"access_token" または "refresh_token"）
            val tokenTypeHint = formParameters.readSingleParameter("token_type_hint").getOrElse { failure ->
                call.respondOAuthError(failure.errorCode, failure.message)
                return@post
            }

            // クライアント認証（共通ユーティリティを使用）
            val credentials = when (val result = extractClientCredentials(formParameters, requireClientAuthentication = true)) {
                is Either.Left -> {
                    call.respondOAuthError(result.value.errorCode, result.value.message)
                    return@post
                }
                is Either.Right -> result.value
            }
            // トークンエンドポイントの制限を迂回した総当たりを防ぐため、同じバックオフでArgon2の実行前に止める
            // 設定は/mineauth reloadで置き換わるため、保持せずリクエストごとに取得する
            val rateLimits = get<OAuthRateLimits>()
            val clientIp = rateLimits.clientIpOf(call)
            rateLimits.checkClientAuthentication(clientIp).onLeft { limited ->
                call.respondRateLimited(limited)
                return@post
            }
            val authenticated = withSpan("oauth.client.authenticate") { span ->
                span.setAttribute(TelemetryAttributes.CLIENT_ID, credentials.clientId)
                authenticateConfidentialClient(credentials, clientIp)
            }
            // 推測にあたる失敗だけがバックオフに数えられる（記録するかはrecordClientAuthFailureが判断する）
            authenticated.onLeft { failure -> rateLimits.recordClientAuthFailure(clientIp, failure) }
            val clientData = when (authenticated) {
                is Either.Left -> {
                    call.respondClientAuthFailure(authenticated.value)
                    return@post
                }
                is Either.Right -> authenticated.value
            }

            // トークンの検証と失効処理（クライアントID・種別ヒントを記録する。トークン本体は記録しない）
            val revocationResult = withSpan("oauth.revoke") { span ->
                span.setAttribute(TelemetryAttributes.CLIENT_ID, clientData.clientId)
                tokenTypeHint?.let { span.setAttribute(TelemetryAttributes.TOKEN_TYPE_HINT, it) }
                revokeToken(token, clientData.clientId)
            }

            when (revocationResult) {
                is Either.Left -> {
                    // 失効を記録できなかった場合に200を返すと、クライアントは失効済みと誤認してしまう
                    // RFC 7009 Section 2.2.1: サーバー側の失敗はエラーで応答し、クライアントの再試行に委ねる
                    plugin.logger.log(
                        Level.SEVERE,
                        "Failed to revoke token for client ${clientData.clientId}: ${revocationResult.value.reason}",
                        revocationResult.value.cause
                    )
                    call.respondOAuthError(OAuthErrorCode.SERVER_ERROR, "Failed to revoke the token")
                    return@post
                }
                is Either.Right -> when (revocationResult.value) {
                    RevocationOutcome.REVOKED ->
                        plugin.logger.info("Token revoked successfully for client: ${clientData.clientId}")
                    // トークンが無効、または他のクライアントのトークンの場合
                    // RFC 7009 Section 2.2: 情報漏洩を防ぐため、トークンの有効性に関わらず200を返す
                    RevocationOutcome.IGNORED ->
                        plugin.logger.info("Token revocation completed without a change: ${clientData.clientId}")
                }
            }

            call.respond(HttpStatusCode.OK)
        }
    }

    /**
     * 失効要求の処理結果（いずれもクライアントには200 OKで応答する）
     */
    private enum class RevocationOutcome {
        // 失効を記録した（既に失効済みの場合を含む）
        REVOKED,

        // 失効の対象外だったため何もしなかった
        IGNORED
    }

    /**
     * トークンを失効させる
     *
     * @param token 失効させるトークン（JWT形式）
     * @param clientId リクエスト元のクライアントID
     * @return 処理結果。失効を記録できなかった場合はRevocationFailure
     */
    private suspend fun revokeToken(
        token: String,
        clientId: String
    ): Either<RevocationFailure, RevocationOutcome> {
        return try {
            // 署名・issuer・種別を検証し、一定期間内の期限切れトークンも許容する
            val jwt: DecodedJWT = try {
                JwtProvider.revocationVerifier.verify(token)
            } catch (e: JWTVerificationException) {
                // 署名不正・issuer不一致・対象外の種別は失効対象外だが、200で応答する（情報漏洩防止）
                return RevocationOutcome.IGNORED.right()
            }

            // 失効処理に必要なクレームを抽出して登録する
            registerRevocation(jwt, clientId)
        } catch (e: CancellationException) {
            // コルーチンのキャンセルは再送出する（握り潰してはいけない）
            throw e
        } catch (e: Exception) {
            // 想定外の例外も失効の失敗として扱い、成功に見せかけない
            RevocationFailure("Unexpected error", e).left()
        }
    }

    /**
     * 検証済みJWTからクレームを抽出し、失効トークンとして登録する
     *
     * リフレッシュトークンの場合は、grace期間の応答の再提示を止め、属するグラントも失効させる（RFC 7009 Section 2.1）。
     * アクセストークンの失効はjti単位のままで、グラントには波及させない
     *
     * @param jwt 検証済み（または期限切れ）のJWT
     * @param clientId リクエスト元のクライアントID
     * @return 処理結果。DBへの登録に失敗した場合はRevocationFailure
     */
    private suspend fun registerRevocation(
        jwt: DecodedJWT,
        clientId: String
    ): Either<RevocationFailure, RevocationOutcome> {
        // JWT IDがないトークンは失効対象外
        val tokenId = jwt.id ?: return RevocationOutcome.IGNORED.right()

        // トークンのclient_idを確認（自分のクライアントのトークンのみ失効可能）
        val tokenClientId = jwt.getClaim("client_id").asString()
        if (tokenClientId != clientId) {
            // 他のクライアントのトークンは失効できないが、200で応答する（情報漏洩防止）
            plugin.logger.warning("Attempted to revoke token of different client: requested=$clientId, token=$tokenClientId")
            return RevocationOutcome.IGNORED.right()
        }

        // トークン種別の判定（RFC 7009 Section 2.1: token_type_hintはあくまでヒントなのでトークン自体を信頼する）
        val tokenType = when (jwt.tokenKind()) {
            TokenKind.ACCESS -> TokenType.ACCESS_TOKEN
            TokenKind.REFRESH -> TokenType.REFRESH_TOKEN
            // Verifierが通すのは上記の2種類のみ
            else -> return RevocationOutcome.IGNORED.right()
        }

        // トークンの有効期限を取得
        val expiresAt = jwt.expiresAt?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it.time), ZoneId.systemDefault())
        } ?: LocalDateTime.now().plusDays(30)

        return either {
            // 失効トークンとして登録
            RevokedTokenRepository.revoke(tokenId, tokenType, clientId, expiresAt)
                .mapLeft { RevocationFailure(it.toString()) }
                .bind()

            if (tokenType == TokenType.REFRESH_TOKEN) {
                // grace期間の応答はこの失効の前に発行されたものなので、ここで取り除かないと再提示で返ってしまう
                // 旧形式のトークンはグラントを持たず、再提示を止める手段はこの無効化だけである
                getKoin().get<RefreshTokenResponseCache>().invalidate(tokenId)

                // 新しいトークンや他のアクセストークンも含めて、同じグラントのトークンをまとめて失効させる
                jwt.grantId()?.let { grantId ->
                    RevokedGrantRepository.revoke(grantId, clientId)
                        .mapLeft { RevocationFailure(it.toString()) }
                        .bind()
                }
            }

            RevocationOutcome.REVOKED
        }
    }
}
