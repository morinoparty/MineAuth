package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import org.bukkit.Bukkit
import org.koin.core.component.KoinComponent
import java.util.*

/**
 * 認証・認可を処理するハンドラー
 * JWTトークンの検証とパーミッションチェックを担当する
 */
class AuthenticationHandler : KoinComponent {

    /**
     * 認証を検証し、認証結果を取得する
     * トークンの種類（ユーザー/サービス）に応じて適切な結果を返す
     *
     * @param call ApplicationCall
     * @return Either<AuthError, AuthResult> 成功時は認証結果
     */
    suspend fun authenticate(call: ApplicationCall): Either<AuthError, AuthResult> = either {
        // JWTPrincipalを取得
        val principal = call.principal<JWTPrincipal>()
        ensure(principal != null) {
            AuthError.NotAuthenticated
        }

        // トークンの種類で分岐
        val tokenType = principal.payload.getClaim("token_type").asString()

        if (tokenType == "service_token") {
            // サービストークン: account_idを返す
            val accountId = principal.payload.getClaim("account_id").asString()
            ensure(accountId != null) {
                AuthError.InvalidToken("Missing account_id claim")
            }
            AuthResult.ServiceAuth(accountId)
        } else {
            // ユーザートークン: playerUniqueIdを返す
            val uuidStr = principal.payload.getClaim("playerUniqueId").asString()
            ensure(uuidStr != null) {
                AuthError.InvalidToken("Missing playerUniqueId claim")
            }

            // UUIDに変換
            try {
                val uuid = UUID.fromString(uuidStr)
                AuthResult.PlayerAuth(uuid)
            } catch (e: IllegalArgumentException) {
                raise(AuthError.InvalidToken("Invalid UUID format: $uuidStr"))
            }
        }
    }

    /**
     * パーミッションを検証する
     * サービスアカウントの場合はBukkitパーミッションチェックをスキップする
     * セキュリティ上の理由から、オフラインプレイヤーはパーミッションチェック不可とする
     *
     * @param authResult 認証結果
     * @param permission 必要なパーミッション文字列
     * @return Either<AuthError, Unit>
     */
    suspend fun checkPermission(
        authResult: AuthResult,
        permission: String
    ): Either<AuthError, Unit> = either {
        // サービスアカウントはBukkitパーミッションチェックをバイパス
        if (authResult is AuthResult.ServiceAuth) {
            return@either
        }

        // プレイヤー認証の場合のみパーミッションチェック
        val playerAuth = authResult as AuthResult.PlayerAuth
        val offlinePlayer = Bukkit.getOfflinePlayer(playerAuth.playerUuid)

        // オンラインプレイヤーを取得
        val onlinePlayer = offlinePlayer.player

        // セキュリティ: オフラインプレイヤーはパーミッションチェック不可
        // オフライン時にパーミッションチェックをスキップすると認可バイパスの脆弱性となる
        ensure(onlinePlayer != null) {
            AuthError.PermissionDenied("Player must be online to check permissions: $permission")
        }

        // オンラインプレイヤーのパーミッションをチェック
        ensure(onlinePlayer.hasPermission(permission)) {
            AuthError.PermissionDenied(permission)
        }
    }

    /**
     * 認証とパーミッションチェックを一度に行う
     *
     * @param call ApplicationCall
     * @param permission 必要なパーミッション文字列
     * @return Either<AuthError, AuthResult> 成功時は認証結果
     */
    suspend fun authenticateAndCheckPermission(
        call: ApplicationCall,
        permission: String
    ): Either<AuthError, AuthResult> = either {
        val result = authenticate(call).bind()
        checkPermission(result, permission).bind()
        result
    }
}
