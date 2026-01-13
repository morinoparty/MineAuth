package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import java.util.*
import kotlin.reflect.KType
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
            is ParameterInfo.QueryParams -> resolveQueryParams(call, paramInfo)
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
        call: ApplicationCall,
        paramInfo: ParameterInfo.QueryParams
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
        // JWTPrincipalを取得
        val principal = call.principal<JWTPrincipal>()
        ensure(principal != null) {
            ResolveError.AuthenticationRequired("JWT token required")
        }

        // playerUniqueIdクレームからUUIDを抽出
        val uuidStr = principal.payload.getClaim("playerUniqueId").asString()
        ensure(uuidStr != null) {
            ResolveError.AuthenticationRequired("Missing playerUniqueId claim")
        }

        val uuid = try {
            UUID.fromString(uuidStr)
        } catch (e: IllegalArgumentException) {
            raise(ResolveError.AuthenticationRequired("Invalid UUID format"))
        }

        // パラメータの型に応じてPlayerまたはOfflinePlayerを返す
        val targetType = paramInfo.type.jvmErasure
        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)

        when {
            targetType == Player::class -> {
                // Playerを要求している場合、オンラインでなければエラー
                offlinePlayer.player ?: raise(ResolveError.PlayerNotFound(uuidStr))
            }
            targetType == OfflinePlayer::class -> {
                // OfflinePlayerを要求している場合はそのまま返す
                offlinePlayer
            }
            else -> {
                // その他の型の場合はオンラインプレイヤーを試みる
                offlinePlayer.player ?: offlinePlayer
            }
        }
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

        val uuidStr = principal.payload.getClaim("playerUniqueId").asString() ?: return null

        val uuid = try {
            UUID.fromString(uuidStr)
        } catch (e: IllegalArgumentException) {
            return null
        }

        val offlinePlayer = Bukkit.getOfflinePlayer(uuid)
        val targetType = paramInfo.type.jvmErasure

        return when {
            targetType == Player::class -> offlinePlayer.player
            targetType == OfflinePlayer::class -> offlinePlayer
            else -> offlinePlayer.player ?: offlinePlayer
        }
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
        } catch (e: Exception) {
            raise(
                ResolveError.TypeConversionFailed(
                    parameterName = paramName,
                    expectedType = targetType.simpleName ?: "Unknown",
                    actualValue = value
                )
            )
        }
    }
}
