package party.morino.mineauth.core.plugin.parser

import arrow.core.Either
import arrow.core.right
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
 * アクセスユーザーをパースするクラス
 * JWTがあれば抽出するが、なければnullを返す（認証は任意）
 *
 * 認証が任意のエンドポイントで使用される
 */
class AccessPlayerParser : ParameterParser<Any?> {

    override suspend fun parse(
        call: ApplicationCall,
        paramInfo: ParameterInfo
    ): Either<ResolveError, Any?> {
        // AccessPlayerでない場合はnullを返す
        val accessParam = paramInfo as? ParameterInfo.AccessPlayer
            ?: return null.right()

        // JWTPrincipalを取得（ない場合はnull）
        val principal = call.principal<JWTPrincipal>()
            ?: return null.right()

        // UUIDを解析（失敗した場合もnull）
        val uuid = parsePlayerUuidOrNull(principal)
            ?: return null.right()

        // プレイヤーを解決
        return resolvePlayerForAccess(uuid, accessParam.type.jvmErasure).right()
    }

    /**
     * JWTからplayerUniqueIdを取り出し、失敗時はnullを返す
     *
     * @param principal JWTPrincipal
     * @return UUIDまたはnull
     */
    private fun parsePlayerUuidOrNull(principal: JWTPrincipal): UUID? {
        val uuidStr = principal.payload.getClaim("playerUniqueId").asString() ?: return null
        return try {
            UUID.fromString(uuidStr)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * 非必須認証でのプレイヤー解決
     *
     * @param uuid プレイヤーUUID
     * @param targetType 要求される型
     * @return Player/OfflinePlayer/nullable
     */
    private fun resolvePlayerForAccess(uuid: UUID, targetType: KClass<*>): Any? {
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        return when {
            targetType == Player::class -> offlinePlayer.player
            targetType == OfflinePlayer::class -> offlinePlayer
            else -> offlinePlayer.player ?: offlinePlayer
        }
    }
}

/**
 * アクセスプレイヤーパーサーのファクトリ
 */
class AccessPlayerParserFactory : ParameterParserFactory {
    private val parser = AccessPlayerParser()

    override fun supports(paramInfo: ParameterInfo): Boolean {
        return paramInfo is ParameterInfo.AccessPlayer
    }

    override fun createParser(paramInfo: ParameterInfo): ParameterParser<*>? {
        return if (supports(paramInfo)) parser else null
    }
}
