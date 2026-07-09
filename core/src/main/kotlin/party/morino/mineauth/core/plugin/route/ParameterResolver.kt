package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.PlayerAccess
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.core.plugin.annotation.CallerKind
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.jvm.jvmErasure

/**
 * リクエストコンテキストからハンドラーメソッドの引数値を解決するクラス
 * 各パラメータタイプに対応した解決ロジックを提供する
 *
 * @property json JSONデシリアライズに使用する共有Jsonインスタンス
 * @property maxBodySize リクエストボディの最大サイズ
 */
class ParameterResolver(
    private val json: Json,
    private val maxBodySize: Int = MAX_BODY_SIZE
) : KoinComponent {

    companion object {
        // リクエストボディの最大サイズ（1MB）
        // DoS攻撃防止のため、過度に大きなボディを拒否する
        const val MAX_BODY_SIZE = 1024 * 1024
    }

    /**
     * リクエストコンテキストからパラメータ値を解決する
     *
     * @param context リクエストコンテキスト
     * @param paramInfo パラメータ情報
     * @return 解決された値（Either で成功/失敗を表現）
     */
    suspend fun resolve(context: RequestContext, paramInfo: ParameterInfo): Either<ResolveError, Any?> = either {
        when (paramInfo) {
            is ParameterInfo.PathParam -> resolvePathParam(context, paramInfo).bind()
            is ParameterInfo.QueryParam -> resolveQueryParam(context, paramInfo).bind()
            is ParameterInfo.QueryMap -> resolveQueryMap(context)
            is ParameterInfo.Body -> resolveBody(context, paramInfo).bind()
            is ParameterInfo.Caller -> resolveCaller(context, paramInfo).bind()
            is ParameterInfo.TargetPlayer -> resolveTargetPlayer(context, paramInfo).bind()
        }
    }

    /**
     * パスパラメータを解決する
     * ディスパッチャのマッチングで抽出済みの値を型変換する
     */
    private fun resolvePathParam(
        context: RequestContext,
        paramInfo: ParameterInfo.PathParam
    ): Either<ResolveError, Any> = either {
        val value = context.pathParams[paramInfo.name]
        ensure(value != null) {
            ResolveError.MissingPathParameter(paramInfo.name)
        }
        convertToType(value, paramInfo.name, paramInfo.type.jvmErasure).bind()
    }

    /**
     * 型付き単一クエリパラメータを解決する
     * nullable型の場合は省略可能、non-nullable型の場合は必須
     */
    private fun resolveQueryParam(
        context: RequestContext,
        paramInfo: ParameterInfo.QueryParam
    ): Either<ResolveError, Any?> = either {
        // 空文字（?cursor= のような指定）は「未指定」として扱う
        val value = context.call.request.queryParameters[paramInfo.name]?.takeIf { it.isNotEmpty() }
        if (value == null) {
            // nullableなら省略時null、non-nullableなら400エラー
            ensure(paramInfo.optional) {
                ResolveError.MissingQueryParameter(paramInfo.name)
            }
            null
        } else {
            convertToType(value, paramInfo.name, paramInfo.type.jvmErasure).bind()
        }
    }

    /**
     * 全クエリパラメータをMapとして解決する
     */
    private fun resolveQueryMap(context: RequestContext): Map<String, String> =
        context.call.request.queryParameters.entries()
            .associate { it.key to (it.value.firstOrNull() ?: "") }

    /**
     * リクエストボディをデシリアライズする
     * シリアライザは登録時に解決済みのものを使用する
     * セキュリティ: ボディサイズを制限してDoS攻撃を防止する
     */
    private suspend fun resolveBody(
        context: RequestContext,
        paramInfo: ParameterInfo.Body
    ): Either<ResolveError, Any?> = either {
        try {
            val call = context.call

            // セキュリティ: Content-Lengthヘッダーを先にチェックしてDoS攻撃を防止
            // メモリに読み込む前に拒否することで、巨大ボディによるメモリ消費を防ぐ
            val contentLength = call.request.headers["Content-Length"]?.toLongOrNull()
            if (contentLength != null && contentLength > maxBodySize) {
                raise(
                    ResolveError.InvalidBodyFormat(
                        IllegalArgumentException("Request body too large (max: $maxBodySize bytes)")
                    )
                )
            }

            // セキュリティ: 上限+1バイトまでしか読まないストリーミング読み込み
            // （Content-Lengthのないchunked転送でも全量バッファリングによるメモリ消費を防ぐ）
            val bodyBytes = call.receiveChannel()
                .readRemaining(maxBodySize.toLong() + 1)
                .readByteArray()
            if (bodyBytes.size > maxBodySize) {
                raise(
                    ResolveError.InvalidBodyFormat(
                        IllegalArgumentException("Request body too large (max: $maxBodySize bytes)")
                    )
                )
            }

            json.decodeFromString(paramInfo.serializer, String(bodyBytes, Charsets.UTF_8))
        } catch (e: CancellationException) {
            // コルーチンのキャンセルは再送出して適切に伝播させる
            throw e
        } catch (e: Exception) {
            raise(ResolveError.InvalidBodyFormat(e))
        }
    }

    /**
     * `@Caller`パラメータにPrincipalを解決する
     *
     * 認証必須エンドポイントでは登録時検証によりPrincipal型とcallers設定の
     * 整合性が保証されているため、通常ここで失敗することはない。
     * 公開エンドポイントでは型が一致しない場合nullを返す。
     */
    private fun resolveCaller(
        context: RequestContext,
        paramInfo: ParameterInfo.Caller
    ): Either<ResolveError, Principal?> = either {
        val principal = context.principal
        val matched = when (paramInfo.kind) {
            CallerKind.ANY -> principal
            CallerKind.USER -> principal as? Principal.User
            CallerKind.SERVICE -> principal as? Principal.Service
        }
        // non-nullableパラメータにnullは渡せないため、認証エラーとして扱う
        ensure(matched != null || paramInfo.optional) {
            ResolveError.AuthenticationRequired("Principal of required type is not available")
        }
        matched
    }

    /**
     * パスセグメントから対象プレイヤーを解決する
     * "me"（ユーザー自身）、UUID、プレイヤー名を受け付け、
     * PlayerAccessポリシーに基づいてアクセス制御を行う
     */
    private fun resolveTargetPlayer(
        context: RequestContext,
        paramInfo: ParameterInfo.TargetPlayer
    ): Either<ResolveError, Any> = either {
        val rawValue = context.pathParams[paramInfo.segment]
        ensure(rawValue != null) {
            ResolveError.MissingPathParameter(paramInfo.segment)
        }

        // @PlayerParamは@Authenticated専用のため、Principalは必ず存在する
        val principal = context.principal
        ensure(principal != null) {
            ResolveError.AuthenticationRequired("Authentication required to resolve player")
        }

        val targetUuid = when (principal) {
            is Principal.User -> resolveTargetForUser(principal, rawValue, paramInfo.access).bind()
            is Principal.Service -> resolveTargetForService(rawValue, paramInfo.access).bind()
        }

        Bukkit.getOfflinePlayer(targetUuid)
    }

    /**
     * ユーザートークンでの対象プレイヤー解決
     * SELF_ONLY / SELF_OR_SERVICE では自分自身のみアクセス可能
     */
    private fun resolveTargetForUser(
        principal: Principal.User,
        rawValue: String,
        access: PlayerAccess
    ): Either<ResolveError, UUID> = either {
        // 自分自身のみアクセス可能なポリシーでは、名前解決を行わずに判定する
        // （Bukkit.getOfflinePlayer(name)はMojangへのブロッキングI/Oを伴うため、
        //  拒否が確定しているリクエストで実行させない）
        if (access != PlayerAccess.ANY_AUTHENTICATED) {
            val isSelf = when {
                rawValue == "me" -> true
                isUuidFormat(rawValue) -> UUID.fromString(rawValue) == principal.uuid
                // 名前指定はローカルキャッシュ由来の自分の名前とのみ照合する
                else -> rawValue == principal.offlinePlayer.name
            }
            ensure(isSelf) {
                ResolveError.AccessDenied("Cannot access another player's data")
            }
            return@either principal.uuid
        }

        when {
            rawValue == "me" -> principal.uuid
            isUuidFormat(rawValue) -> UUID.fromString(rawValue)
            else -> Bukkit.getOfflinePlayer(rawValue).uniqueId
        }
    }

    /**
     * サービストークンでの対象プレイヤー解決
     * SELF_ONLYポリシーではサービストークンは拒否される
     */
    private fun resolveTargetForService(
        rawValue: String,
        access: PlayerAccess
    ): Either<ResolveError, UUID> = either {
        // サービスアカウントには「自分自身」が存在しない
        ensure(rawValue != "me") {
            ResolveError.AccessDenied("Service accounts cannot use 'me'")
        }
        ensure(access != PlayerAccess.SELF_ONLY) {
            ResolveError.AccessDenied("This endpoint does not allow service token access to player data")
        }

        if (isUuidFormat(rawValue)) {
            UUID.fromString(rawValue)
        } else {
            Bukkit.getOfflinePlayer(rawValue).uniqueId
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
        targetType: KClass<*>
    ): Either<ResolveError, Any> = either {
        try {
            when (targetType) {
                String::class -> value
                Int::class -> value.toInt()
                Long::class -> value.toLong()
                Double::class -> value.toDouble()
                Float::class -> value.toFloat()
                Boolean::class -> value.toBooleanStrict()
                UUID::class -> UUID.fromString(value)
                // 登録時に型を検証済みのため、ここには到達しない
                else -> raise(
                    ResolveError.TypeConversionFailed(
                        parameterName = paramName,
                        expectedType = targetType.simpleName ?: "Unknown",
                        actualValue = value
                    )
                )
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
     * 文字列がUUID形式かどうかを判定する
     */
    private fun isUuidFormat(value: String): Boolean =
        try {
            UUID.fromString(value)
            true
        } catch (e: IllegalArgumentException) {
            false
        }
}
