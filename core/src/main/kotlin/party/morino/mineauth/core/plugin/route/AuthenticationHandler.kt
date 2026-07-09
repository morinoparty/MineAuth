package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.auth.ServicePrincipal
import party.morino.mineauth.core.plugin.auth.UserPrincipal
import java.util.UUID

/**
 * 認証・認可を処理するハンドラー
 * JWTトークンからPrincipalを構築し、エンドポイントのアクセス制御を適用する
 *
 * v1からの重要な変更:
 * - サービストークンによる暗黙のパーミッションバイパスを廃止。
 *   サービストークンは`@Authenticated(callers = [..., CallerType.SERVICE])`で
 *   明示的に許可されたエンドポイントのみ呼び出せる
 * - オフラインプレイヤーのパーミッション評価不能はPermissionDeniedと区別して返す
 */
class AuthenticationHandler : KoinComponent {

    /**
     * 認証必須エンドポイントのPrincipalを取得する
     * トークン種別チェック・パーミッションチェックを含む
     *
     * @param call ApplicationCall
     * @param access エンドポイントのアクセス制御設定
     * @return 認証済みPrincipal、失敗時はAuthError
     */
    fun requirePrincipal(
        call: ApplicationCall,
        access: EndpointAccess.Authenticated
    ): Either<AuthError, Principal> = either {
        val jwtPrincipal = call.principal<JWTPrincipal>()
        ensure(jwtPrincipal != null) {
            AuthError.NotAuthenticated
        }

        val principal = buildPrincipal(jwtPrincipal).bind()

        // トークン種別がエンドポイントの許可設定に含まれるかチェック
        val callerType = when (principal) {
            is Principal.User -> CallerType.USER
            is Principal.Service -> CallerType.SERVICE
        }
        ensure(callerType in access.callers) {
            AuthError.WrongTokenType(access.callers)
        }

        // パーミッションチェック（ユーザートークンのみ対象）
        // サービストークンは管理者発行の信頼された資格情報であり、
        // callers設定による明示的な許可がアクセス制御となる
        if (access.permission != null && principal is Principal.User) {
            // セキュリティ: オフラインプレイヤーはパーミッション評価ができないため拒否する
            // （評価をスキップして許可すると認可バイパスの脆弱性となる）
            ensure(principal.onlinePlayer != null) {
                AuthError.PlayerOffline(access.permission)
            }
            ensure(principal.hasPermission(access.permission)) {
                AuthError.PermissionDenied(access.permission)
            }
        }

        principal
    }

    /**
     * 公開エンドポイント用にPrincipalを任意で取得する
     *
     * セキュリティ: Authorizationヘッダーが存在するのに検証済みPrincipalがない場合は
     * 「無効なトークン」として401を返す（黙って未認証扱いにしない）
     *
     * @param call ApplicationCall
     * @return Principal（未認証の場合null）、無効トークンの場合はAuthError
     */
    fun optionalPrincipal(call: ApplicationCall): Either<AuthError, Principal?> = either {
        val jwtPrincipal = call.principal<JWTPrincipal>()
        if (jwtPrincipal == null) {
            // トークンが提示されているのに検証を通っていない場合は無効トークン
            ensure(call.request.headers[HttpHeaders.Authorization] == null) {
                AuthError.InvalidToken("Token validation failed")
            }
            return@either null
        }
        buildPrincipal(jwtPrincipal).bind()
    }

    /**
     * JWTPrincipalからAPI公開用のPrincipalを構築する
     *
     * @param jwtPrincipal 検証済みのJWTPrincipal
     * @return 構築されたPrincipal
     */
    private fun buildPrincipal(jwtPrincipal: JWTPrincipal): Either<AuthError, Principal> = either {
        val payload = jwtPrincipal.payload
        val tokenType = payload.getClaim("token_type").asString()

        // アクセストークン以外（refresh_token等）はここで拒否する（多層防御）
        ensure(tokenType == "token" || tokenType == "service_token") {
            AuthError.InvalidToken("Unsupported token_type: $tokenType")
        }

        if (tokenType == "service_token") {
            // サービストークン: account_idからServicePrincipalを構築
            val accountId = payload.getClaim("account_id").asString()
            ensure(accountId != null) {
                AuthError.InvalidToken("Missing account_id claim")
            }
            ServicePrincipal(accountId = accountId)
        } else {
            // ユーザートークン: playerUniqueIdからUserPrincipalを構築
            val uuidStr = payload.getClaim("playerUniqueId").asString()
            ensure(uuidStr != null) {
                AuthError.InvalidToken("Missing playerUniqueId claim")
            }
            val uuid = try {
                UUID.fromString(uuidStr)
            } catch (e: IllegalArgumentException) {
                raise(AuthError.InvalidToken("Invalid UUID format"))
            }

            // scopeクレームはスペース区切り文字列（OAuth2標準）
            val scopes = payload.getClaim("scope").asString()
                ?.split(" ")
                ?.filter { it.isNotBlank() }
                ?.toSet()
                ?: emptySet()

            UserPrincipal(
                uuid = uuid,
                scopes = scopes,
                clientId = payload.getClaim("client_id").asString()
            )
        }
    }
}
