package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveText
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import java.util.*
import kotlin.reflect.jvm.jvmErasure

/**
 * Ktorのコンテキストからパラメータ値を解決するクラス
 * 各パラメータタイプに対応した解決ロジックを提供する
 */
class ParameterResolver(
    private val json: Json = Json { ignoreUnknownKeys = true }
) : KoinComponent {

    /**
     * Ktorのコンテキストからパラメータ値を解決する
     *
     * @param call Ktorの ApplicationCall
     * @param paramInfo パラメータ情報
     * @return 解決された値（Either で成功/失敗を表現）
     */
    suspend fun resolve(call: ApplicationCall, paramInfo: ParameterInfo): Either<ResolveError, Any?> = either {
        when (paramInfo) {
            is ParameterInfo.PathParam -> resolvePathParam(call, paramInfo).bind()
            is ParameterInfo.QueryParams -> resolveQueryParams(call)
            is ParameterInfo.Body -> resolveBody(call, paramInfo).bind()
            is ParameterInfo.AuthenticatedPlayer -> resolveAuthenticatedPlayer(call, paramInfo).bind()
            is ParameterInfo.AccessPlayer -> resolveAccessPlayer(call, paramInfo)
        }
    }

    /**
     * パスパラメータを解決する
     * パス内の{id}形式のパラメータを取得する
     *
     * @param call ApplicationCall
     * @param paramInfo パスパラメータ情報
     * @return 解決された値
     */
    private fun resolvePathParam(
        call: ApplicationCall,
        paramInfo: ParameterInfo.PathParam
    ): Either<ResolveError, Any?> = either {
        val names = paramInfo.names
        val targetType = paramInfo.type.jvmErasure

        if (names.size == 1) {
            // 単一パラメータの場合
            val name = names.first()
            val value = call.parameters[name]
            ensure(value != null) {
                ResolveError.MissingPathParameter(name)
            }

            // 型に応じて変換
            convertToType(value, name, targetType).bind()
        } else {
            // 複数パラメータの場合はMapで返す
            val result = mutableMapOf<String, String>()
            for (name in names) {
                val value = call.parameters[name]
                ensure(value != null) {
                    ResolveError.MissingPathParameter(name)
                }
                result[name] = value
            }
            result
        }
    }

    /**
     * クエリパラメータを解決する
     * 全クエリパラメータをMapとして返す
     *
     * @param call ApplicationCall
     * @param paramInfo クエリパラメータ情報
     * @return クエリパラメータのMap
     */
    private fun resolveQueryParams(
        call: ApplicationCall
    ): Map<String, String> {
        return call.request.queryParameters.entries()
            .associate { it.key to (it.value.firstOrNull() ?: "") }
    }

    /**
     * リクエストボディをデシリアライズする
     *
     * @param call ApplicationCall
     * @param paramInfo ボディパラメータ情報
     * @return デシリアライズされたオブジェクト
     */
    private suspend fun resolveBody(
        call: ApplicationCall,
        paramInfo: ParameterInfo.Body
    ): Either<ResolveError, Any> = either {
        try {
            val bodyText = call.receiveText()
            val serializer = serializer(paramInfo.type)
            json.decodeFromString(serializer, bodyText) as Any
        } catch (e: Exception) {
            raise(ResolveError.InvalidBodyFormat(e))
        }
    }

    /**
     * 認証済みプレイヤーを取得する
     * JWTからplayerUniqueIdを抽出してPlayerを取得する
     *
     * @param call ApplicationCall
     * @param paramInfo 認証済みプレイヤー情報
     * @return Player または OfflinePlayer
     */
    private suspend fun resolveAuthenticatedPlayer(
        call: ApplicationCall,
        paramInfo: ParameterInfo.AuthenticatedPlayer
    ): Either<ResolveError, Any> = either {
        // JWTPrincipalを取得して必須認証を確認
        val principal = call.principal<JWTPrincipal>()
        ensure(principal != null) {
            ResolveError.AuthenticationRequired("JWT token required")
        }

        // playerUniqueIdクレームをUUIDとして解決
        val uuid = resolvePlayerUuid(principal).bind()

        // パラメータの型に応じてPlayerまたはOfflinePlayerを返す
        resolvePlayerForAuth(uuid, paramInfo.type.jvmErasure).bind()
    }

    /**
     * アクセスユーザーを取得する（認証なしでも動作）
     * JWTがあれば抽出、なければnull
     *
     * @param call ApplicationCall
     * @param paramInfo アクセスプレイヤー情報
     * @return Player または null
     */
    private suspend fun resolveAccessPlayer(
        call: ApplicationCall,
        paramInfo: ParameterInfo.AccessPlayer
    ): Any? {
        val principal = call.principal<JWTPrincipal>() ?: return null
        val uuid = parsePlayerUuidOrNull(principal) ?: return null
        return resolvePlayerForAccess(uuid, paramInfo.type.jvmErasure)
    }

    /**
     * 文字列を指定された型に変換する
     *
     * @param value 変換する文字列
     * @param paramName パラメータ名（エラーメッセージ用）
     * @param targetType 変換先の型
     * @return 変換された値
     */
    private fun convertToType(
        value: String,
        paramName: String,
        targetType: kotlin.reflect.KClass<*>
    ): Either<ResolveError, Any> = either {
        try {
            when (targetType) {
                String::class -> value
                Int::class -> value.toInt()
                Long::class -> value.toLong()
                Double::class -> value.toDouble()
                Float::class -> value.toFloat()
                Boolean::class -> value.toBoolean()
                UUID::class -> UUID.fromString(value)
                else -> value // デフォルトは文字列として返す
            }
        } catch (e: IllegalArgumentException) {
            raise(
                ResolveError.TypeConversionFailed(
                    parameterName = paramName,
                    expectedType = targetType.simpleName ?: "Unknown",
                    actualValue = value
                )
            )
        }
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
     * 認証済みアクセスでのプレイヤー解決
     *
     * @param uuid プレイヤーUUID
     * @param targetType 要求される型
     * @return PlayerまたはOfflinePlayer
     */
    private fun resolvePlayerForAuth(
        uuid: UUID,
        targetType: kotlin.reflect.KClass<*>
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

    /**
     * 非必須認証でのプレイヤー解決
     *
     * @param uuid プレイヤーUUID
     * @param targetType 要求される型
     * @return Player/OfflinePlayer/nullable
     */
    private fun resolvePlayerForAccess(uuid: UUID, targetType: kotlin.reflect.KClass<*>): Any? {
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        return when {
            targetType == Player::class -> offlinePlayer.player
            targetType == OfflinePlayer::class -> offlinePlayer
            else -> offlinePlayer.player ?: offlinePlayer
        }
    }
}
