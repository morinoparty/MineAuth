package party.morino.mineauth.core.web.router.auth.oauth

import arrow.core.Either
import arrow.core.getOrElse
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.DecodedJWT
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.model.tokenKind
import party.morino.mineauth.core.web.ratelimit.OAuthRateLimits
import party.morino.mineauth.core.web.ratelimit.clientIpOf
import party.morino.mineauth.core.web.ratelimit.respondClientAuthFailure
import party.morino.mineauth.core.web.ratelimit.respondRateLimited
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.authenticateConfidentialClient
import party.morino.mineauth.core.web.router.auth.oauth.OAuthValidation.extractClientCredentials
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.web.telemetry.withSpan
import java.sql.SQLException
import java.util.UUID

/**
 * RFC 7662 OAuth 2.0 Token Introspection エンドポイント
 *
 * リソースサーバーがトークンの有効性とメタデータを問い合わせるためのエンドポイント
 * https://datatracker.ietf.org/doc/html/rfc7662
 */
object IntrospectRouter : KoinComponent {
    private val plugin: MineAuth by inject()
    private val tokenStateValidator: TokenStateValidator by inject()

    /**
     * POST /oauth2/introspect エンドポイントを登録する
     *
     * RFC 7662に基づくトークンイントロスペクションエンドポイント
     * - クライアント認証が必須（Confidentialクライアント）
     * - 認証後、サーバー管理者に許可されていないクライアントは403で拒否する（RFC 7662 Section 4）
     * - 状態の検証を通ったアクセス・リフレッシュ・サービストークンの場合: メタデータ付きで active=true を返す
     * - それ以外（無効/期限切れ/失効済み/ID Token/クライアントやアカウントの削除後）: active=false のみを返す
     */
    fun Route.introspectRouter() {
        post("/introspect") {
            val formParameters = call.receiveParameters()

            // RFC 7662 Section 2.1: 必須パラメータ token（空値は省略扱い、重複はinvalid_request）
            val token = formParameters.readSingleParameter("token").getOrElse { failure ->
                call.respondOAuthError(failure.errorCode, failure.message)
                return@post
            }
            if (token == null) {
                call.respondOAuthError(OAuthErrorCode.INVALID_REQUEST, "Missing required parameter: token")
                return@post
            }

            // RFC 7662 Section 2.1: token_type_hintは任意で値は判定に使わない。重複だけをinvalid_requestとして拒否する
            formParameters.readSingleParameter("token_type_hint").onLeft { failure ->
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
            // リソースサーバーは正当に毎秒何度も問い合わせるので、リクエスト数のバケットは使わない
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

            // RFC 7662 Section 4: 個別に認可されたリソースサーバーだけが利用できる
            // トークンの検証より先に判定し、権限のない呼び出し元にはトークンの状態を一切伝えない
            // active=falseを返すと更新後に全トークンの障害に見えるため、明示的に拒否して原因を伝える
            if (!clientData.introspectionAllowed) {
                call.respondOAuthError(
                    OAuthErrorCode.UNAUTHORIZED_CLIENT,
                    "This client is not allowed to introspect tokens. A server administrator must grant " +
                        "the permission with: /mineauth client introspection allow ${clientData.clientId}",
                    HttpStatusCode.Forbidden
                )
                return@post
            }

            // トークンを検証してイントロスペクションレスポンスを生成（結果のactiveを記録する）
            val response = withSpan("oauth.introspect") { span ->
                introspectToken(token).also {
                    span.setAttribute(TelemetryAttributes.AUTH_RESULT, if (it.active) "active" else "inactive")
                }
            }

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
        // 署名・issuer・有効期限・種別を検証する
        val jwt = try {
            JwtProvider.introspectionVerifier.verify(token)
        } catch (e: JWTVerificationException) {
            // 署名不正・issuer不一致・期限切れ・対象外の種別 → inactive
            return IntrospectionResponse(active = false)
        }

        // Bearer認証と同じ規則で状態を判定する（RFC 7662 Section 4）
        val kind = jwt.tokenKind()
        val state = try {
            tokenStateValidator.validate(kind, jwt)
        } catch (e: SQLException) {
            // 失効していないことを確認できないトークンをactiveとは報告できないため、inactiveとして扱う
            plugin.logger.severe("Token introspection failed to read the token state: ${e.message}")
            return IntrospectionResponse(active = false)
        }

        return state.fold(
            { IntrospectionResponse(active = false) },
            { activeResponse(jwt, kind) }
        )
    }

    /**
     * 有効なトークンのメタデータを持つレスポンスを生成する
     *
     * @param jwt 検証済みのJWT
     * @param kind トークンの種別
     * @return active=trueのレスポンス
     */
    private fun activeResponse(jwt: DecodedJWT, kind: TokenKind): IntrospectionResponse {
        // プレイヤーUUIDを取得（usernameフィールドに使用）
        val playerUniqueId = jwt.getClaim("playerUniqueId").asString()
        val username = playerUniqueId?.let {
            try {
                org.bukkit.Bukkit.getOfflinePlayer(UUID.fromString(it)).name
            } catch (e: IllegalArgumentException) {
                // UUID形式でない場合はusernameを省略する
                null
            }
        }

        return IntrospectionResponse(
            active = true,
            scope = jwt.getClaim("scope").asString(),
            clientId = jwt.getClaim("client_id").asString(),
            username = username,
            tokenType = responseTokenType(kind),
            exp = jwt.expiresAt?.time?.div(1000),
            iat = jwt.issuedAt?.time?.div(1000),
            nbf = jwt.notBefore?.time?.div(1000),
            sub = playerUniqueId,
            aud = jwt.audience?.firstOrNull(),
            iss = jwt.issuer,
            jti = jwt.id
        )
    }

    /**
     * レスポンスのtoken_typeを種別から決定する
     * RFC 6749 Section 5.1のトークンタイプはリソースアクセスの方式を表すため、リフレッシュトークンには付けない
     *
     * @param kind トークンの種別
     * @return Bearerトークンとして使える種別なら"Bearer"、それ以外はnull
     */
    private fun responseTokenType(kind: TokenKind): String? = when (kind) {
        TokenKind.ACCESS, TokenKind.SERVICE -> "Bearer"
        else -> null
    }
}
