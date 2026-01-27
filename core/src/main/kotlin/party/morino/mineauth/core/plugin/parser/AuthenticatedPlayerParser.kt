package party.morino.mineauth.core.plugin.parser

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.route.ResolveError
import java.util.*
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmErasure

/**
 * 認証済みプレイヤーをパースするクラス
 * JWTからplayerUniqueIdを抽出してPlayerまたはOfflinePlayerを取得する
 *
 * 認証が必須のエンドポイントで使用される
 */
class AuthenticatedPlayerParser : ParameterParser<Any> {

    override suspend fun parse(
        call: ApplicationCall,
        paramInfo: ParameterInfo
    ): Either<ResolveError, Any> = either {
        // AuthenticatedPlayerでない場合はエラー
        val authParam = paramInfo as? ParameterInfo.AuthenticatedPlayer
            ?: raise(ResolveError.AuthenticationRequired("Invalid parameter type"))

        // JWTPrincipalを取得して必須認証を確認
        val principal = call.principal<JWTPrincipal>()
        ensure(principal != null) {
            ResolveError.AuthenticationRequired("JWT token required")
        }

        // playerUniqueIdクレームをUUIDとして解決
        val uuid = resolvePlayerUuid(principal).bind()

        // パラメータの型に応じてPlayerまたはOfflinePlayerを返す
        resolvePlayerForAuth(uuid, authParam.type.jvmErasure).bind()
    }

    /**
     * JWTからplayerUniqueIdをUUIDとして取得する
     *
     * @param principal JWTPrincipal
     * @return 変換後のUUID
     */
    private fun resolvePlayerUuid(principal: JWTPrincipal): Either<ResolveError, UUID> = either {
        val uuidStr = principal.payload.getClaim("playerUniqueId").asString()
        ensure(uuidStr != null) {
            ResolveError.AuthenticationRequired("Missing playerUniqueId claim")
        }
        try {
            UUID.fromString(uuidStr)
        } catch (e: IllegalArgumentException) {
            raise(ResolveError.AuthenticationRequired("Invalid UUID format"))
        }
    }

    /**
     * 認証済みアクセスでのプレイヤー解決
     *
     * @param uuid プレイヤーUUID
     * @param targetType 要求される型
     * @return PlayerまたはOfflinePlayer
     */
    private fun resolvePlayerForAuth(
        uuid: UUID,
        targetType: KClass<*>
    ): Either<ResolveError, Any> = either {
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        when {
            targetType == Player::class -> {
                // オンラインプレイヤー必須
                offlinePlayer.player ?: raise(ResolveError.PlayerNotFound(uuid.toString()))
            }
            targetType == OfflinePlayer::class -> offlinePlayer
            else -> offlinePlayer.player ?: offlinePlayer
        }
    }
}

/**
 * 認証済みプレイヤーパーサーのファクトリ
 */
class AuthenticatedPlayerParserFactory : ParameterParserFactory {
    private val parser = AuthenticatedPlayerParser()

    override fun supports(paramInfo: ParameterInfo): Boolean {
        return paramInfo is ParameterInfo.AuthenticatedPlayer
    }

    override fun createParser(paramInfo: ParameterInfo): ParameterParser<*>? {
        return if (supports(paramInfo)) parser else null
    }
}
