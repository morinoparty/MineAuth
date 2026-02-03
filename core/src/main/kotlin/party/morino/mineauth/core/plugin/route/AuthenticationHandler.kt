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
     * 認証を検証し、プレイヤーUUIDを取得する
     *
     * @param call ApplicationCall
     * @return Either<AuthError, UUID> 成功時はプレイヤーUUID
     */
    suspend fun authenticate(call: ApplicationCall): Either<AuthError, UUID> = either {
        // JWTPrincipalを取得
        val principal = call.principal<JWTPrincipal>()
        ensure(principal != null) {
            AuthError.NotAuthenticated
        }

        // playerUniqueIdクレームを取得
        val uuidStr = principal.payload.getClaim("playerUniqueId").asString()
        ensure(uuidStr != null) {
            AuthError.InvalidToken("Missing playerUniqueId claim")
        }

        // UUIDに変換
        try {
            UUID.fromString(uuidStr)
        } catch (e: IllegalArgumentException) {
            raise(AuthError.InvalidToken("Invalid UUID format: $uuidStr"))
        }
    }

    /**
     * パーミッションを検証する
     * セキュリティ上の理由から、オフラインプレイヤーはパーミッションチェック不可とする
     *
     * @param playerUuid プレイヤーUUID
     * @param permission 必要なパーミッション文字列
     * @return Either<AuthError, Unit>
     */
    suspend fun checkPermission(
        playerUuid: UUID,
        permission: String
    ): Either<AuthError, Unit> = either {
        val offlinePlayer = Bukkit.getOfflinePlayer(playerUuid)

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
     * @return Either<AuthError, UUID> 成功時はプレイヤーUUID
     */
    suspend fun authenticateAndCheckPermission(
        call: ApplicationCall,
        permission: String
    ): Either<AuthError, UUID> = either {
        val uuid = authenticate(call).bind()
        checkPermission(uuid, permission).bind()
        uuid
    }
}
