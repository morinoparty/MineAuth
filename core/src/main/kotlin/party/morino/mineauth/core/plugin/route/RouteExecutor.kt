package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.util.reflect.TypeInfo
import io.opentelemetry.api.common.Attributes
import org.koin.core.component.KoinComponent
import org.slf4j.LoggerFactory
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.execution.ExecutionError
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.serialization.PluginSerialization
import party.morino.mineauth.core.web.telemetry.TelemetryAttributes
import party.morino.mineauth.core.web.telemetry.withSpan
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaType

/**
 * ハンドラーメソッドを実行するクラス
 * Cloud (Incendo/cloud) のパターンに基づきファクトリを使用してハンドラーを選択する
 *
 * パラメータ解決、メソッド実行、レスポンス生成を担当する
 * （認証・認可はディスパッチャで実施済みの前提）
 */
class RouteExecutor(
    private val parameterResolver: ParameterResolver,
    private val executionHandlerFactory: MethodExecutionHandlerFactory
) : KoinComponent {

    /**
     * エンドポイントメタデータに基づいてハンドラーを実行する
     *
     * @param context 認証・パスマッチング済みのリクエストコンテキスト
     * @param metadata エンドポイントメタデータ
     */
    suspend fun execute(context: RequestContext, metadata: EndpointMetadata) {
        val call = context.call

        // アドオンルート全体の処理を1つのスパンで計測する（全アドオンルートの単一チョークポイント）
        val attributes = Attributes.builder()
            .put(TelemetryAttributes.HANDLER_CLASS, metadata.handlerInstance::class.qualifiedName ?: "unknown")
            .put(TelemetryAttributes.ENDPOINT_PATH, metadata.path)
            .put(TelemetryAttributes.ENDPOINT_METHOD, metadata.httpMethod.name)
            .build()
        withSpan("mineauth.plugin.handler", attributes = attributes) {
            // パラメータ解決に失敗した場合はレスポンス済みで終了
            val resolvedParams = resolveParameters(context, metadata) ?: return@withSpan

            // ファクトリ経由でハンドラーを取得
            val handler = executionHandlerFactory.createHandler(metadata)

            // ハンドラーを実行して結果を処理
            when (val result = handler.execute(metadata, resolvedParams)) {
                is Either.Left -> handleExecutionError(call, result.value, metadata, resolvedParams)
                is Either.Right -> handleResult(call, result.value, metadata)
            }
        }
    }

    /**
     * パラメータを順序通りに解決する
     *
     * @param context リクエストコンテキスト
     * @param metadata エンドポイントメタデータ
     * @return 解決結果、失敗時はnull
     */
    private suspend fun resolveParameters(
        context: RequestContext,
        metadata: EndpointMetadata
    ): List<Any?>? {
        val resolvedParams = mutableListOf<Any?>()
        for (paramInfo in metadata.parameters) {
            when (val result = parameterResolver.resolve(context, paramInfo)) {
                is Either.Left -> {
                    handleResolveError(context.call, result.value)
                    return null
                }
                is Either.Right -> resolvedParams.add(result.value)
            }
        }
        return resolvedParams
    }

    /**
     * メソッドの戻り値をHTTPレスポンスに変換する
     *
     * 戻り値がEither<HttpError, T>の場合はアンラップし、
     * LeftならHttpErrorレスポンス、Rightなら値をシリアライズして返す。
     *
     * 値のレスポンス化は[respondSerialized]に委譲する。利用側プラグインがserializationを
     * shadeしていても直列化でき、直列化失敗もKtor既定のtext/plain 500ではなくMineAuthの
     * サニタイズ済みJSONエラーに収まる。
     *
     * @param call ApplicationCall
     * @param result メソッドの戻り値
     * @param metadata エンドポイントメタデータ
     */
    private suspend fun handleResult(call: ApplicationCall, result: Any?, metadata: EndpointMetadata) {
        // Either<HttpError, T>のアンラップ（アドオンのクラスローダー差異に備えてリフレクションで処理）
        val value = if (metadata.returnsEither && result != null) {
            when (val unwrapped = unwrapEither(result)) {
                is EitherUnwrap.Success -> unwrapped.value
                is EitherUnwrap.HttpFailure -> {
                    respondHttpError(call, unwrapped.error)
                    return
                }
                is EitherUnwrap.Unknown -> {
                    logger.error("Unexpected Either shape from handler: {}", sanitizeForLog(unwrapped.reason))
                    respondInternalServerError(call)
                    return
                }
            }
        } else {
            result
        }

        when (value) {
            null -> call.respond(HttpStatusCode.NoContent)
            is Unit -> call.respond(HttpStatusCode.OK)
            else -> respondSerialized(call, value, metadata)
        }
    }

    /**
     * 戻り値をレスポンスとして返す
     *
     * まずMineAuth本体のランタイムでシリアライザを解決できるかを確認し、可能なら
     * 既存どおり`call.respond(value, TypeInfo)`で返す（String等のKtor特殊型の扱いを含め
     * 共有ランタイム時の挙動を完全に維持する）。解決できない場合はクラスローダ分裂と判断し、
     * 利用側プラグインのクラスローダで直列化する（[PluginSerialization]）。
     *
     * 分裂ケースの直列化は制御下の`try/catch`で捕捉し、失敗時は汎用JSONエラー（500）に
     * 変換する。これにより例外がKtorのレスポンスパイプラインへ漏れてtext/plainの500になる
     * ことを防ぐ。
     *
     * @param call ApplicationCall
     * @param value 直列化する戻り値（非null）
     * @param metadata エンドポイントメタデータ
     */
    private suspend fun respondSerialized(call: ApplicationCall, value: Any, metadata: EndpointMetadata) {
        // 標準パス：MineAuth自身のランタイムで解決できるなら既存挙動を完全に維持する
        // 解決可否は登録時に判定済み（[EndpointMetadata.responseResolvableByCore]）で、
        // リクエスト時に serializer(KType) を呼ばないため例外がパイプラインへ漏れることはない
        if (metadata.responseResolvableByCore) {
            call.respond(value, resolveTypeInfo(metadata))
            return
        }

        // クラスローダ分裂：利用側が自前のserializationランタイムを同梱している場合、
        // MineAuth側のserializer(KType)は別Classの生成シリアライザをキャストできず解決に失敗する。
        // 戻り値を提供したハンドラー（＝利用側プラグイン）のクラスローダで直列化する。
        val jsonText = try {
            val consumerClassLoader = metadata.handlerInstance.javaClass.classLoader
            PluginSerialization.encodeToString(consumerClassLoader, metadata.responseType.javaType, value)
        } catch (e: Exception) {
            // 直列化失敗の詳細はログにのみ出力（サニタイズしてログ注入を防止）
            logger.error(
                "Response serialization failed for {} {}: {}",
                metadata.httpMethod.name,
                sanitizeForLog(metadata.path),
                sanitizeForLog("${e.javaClass.name}: ${e.message}")
            )
            respondInternalServerError(call)
            return
        }
        call.respondText(jsonText, ContentType.Application.Json, HttpStatusCode.OK)
    }

    /** Eitherアンラップの結果を表すsealed class */
    private sealed class EitherUnwrap {
        data class Success(val value: Any?) : EitherUnwrap()
        data class HttpFailure(val error: HttpError) : EitherUnwrap()
        data class Unknown(val reason: String) : EitherUnwrap()
    }

    /**
     * ArrowのEitherをリフレクションでアンラップする
     *
     * アドオンがArrowをshade/relocateしている場合、coreのEitherクラスとは
     * 別クラスになるため、クラス名のsuffix比較とリフレクションで処理する。
     * HttpErrorはMineAuth本体のクラスローダーから提供されるため直接型チェック可能。
     */
    private fun unwrapEither(result: Any): EitherUnwrap {
        val className = result.javaClass.name
        // パッケージ境界を含めて比較し、無関係な同名クラスの誤判定を防ぐ
        fun matches(suffix: String) = className == suffix || className.endsWith(".$suffix")
        return try {
            when {
                matches("arrow.core.Either\$Right") -> {
                    EitherUnwrap.Success(result.javaClass.getMethod("getValue").invoke(result))
                }
                matches("arrow.core.Either\$Left") -> {
                    val leftValue = result.javaClass.getMethod("getValue").invoke(result)
                    if (leftValue is HttpError) {
                        EitherUnwrap.HttpFailure(leftValue)
                    } else {
                        EitherUnwrap.Unknown("Left value is not HttpError: ${leftValue?.javaClass?.name}")
                    }
                }
                else -> EitherUnwrap.Unknown("Not an Either instance: $className")
            }
        } catch (e: ReflectiveOperationException) {
            EitherUnwrap.Unknown("Failed to unwrap Either: ${e.message}")
        }
    }

    /**
     * 登録時に解決済みのレスポンス型から`TypeInfo`を構築する
     *
     * リフレクション経由の呼び出しでは戻り値の静的型情報（ジェネリクス含む）が失われ、
     * Ktorの`guessSerializer`によるリスト要素の型推測が外部プラグインのクラスに対して失敗するため、
     * 登録時に解決した宣言上のレスポンス型（`KType`）から`TypeInfo`を構築して`respond`に明示的に渡す。
     *
     * @param metadata エンドポイントメタデータ
     * @return レスポンス型に基づく`TypeInfo`
     */
    private fun resolveTypeInfo(metadata: EndpointMetadata): TypeInfo {
        val responseType = metadata.responseType
        val classifier = responseType.classifier as? KClass<*> ?: Any::class
        return TypeInfo(classifier, responseType.javaType, responseType)
    }

    /**
     * HttpErrorをHTTPレスポンスに変換する
     */
    private suspend fun respondHttpError(call: ApplicationCall, error: HttpError) {
        call.respond(
            HttpStatusCode.fromValue(error.status.code),
            ErrorResponse.fromDetails(error.message, error.details, error.code)
        )
    }

    /**
     * 実行エラーをHTTPレスポンスに変換する
     * セキュリティ: 内部エラー詳細はログにのみ出力し、クライアントには汎用メッセージを返す
     *
     * @param call ApplicationCall
     * @param error 実行エラー
     * @param metadata エンドポイントメタデータ（ログ用）
     * @param resolvedParams 解決済みパラメータ（ログ用）
     */
    private suspend fun handleExecutionError(
        call: ApplicationCall,
        error: ExecutionError,
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ) {
        when (error) {
            is ExecutionError.InvocationFailed -> {
                // 詳細はログにのみ出力（サニタイズしてログ注入を防止）
                logger.error(
                    "InvocationFailed: {}: {}",
                    sanitizeForLog(error.cause.javaClass.name),
                    sanitizeForLog(error.cause.message)
                )
                // クライアントには汎用メッセージを返す
                respondInternalServerError(call)
            }

            is ExecutionError.MethodNotFound -> {
                logger.error("MethodNotFound: {}", sanitizeForLog(error.methodName))
                respondInternalServerError(call)
            }

            is ExecutionError.ArgumentTypeMismatch -> {
                val expectedParams = metadata.method.parameters
                    .filter { it.kind != KParameter.Kind.INSTANCE }
                    .joinToString(", ") { "${it.name}: ${it.type}" }
                val actualParams = resolvedParams.mapIndexed { index, value ->
                    "arg$index: ${value?.let { it::class.simpleName } ?: "null"}"
                }.joinToString(", ")
                // 詳細はログにのみ出力
                logger.error(
                    "ArgumentTypeMismatch: Method={}, Expected=[{}], Actual=[{}]",
                    metadata.method.name, expectedParams, actualParams
                )
                // クライアントには汎用メッセージを返す
                respondInternalServerError(call)
            }

            is ExecutionError.HttpErrorThrown -> {
                // HttpErrorは意図的にスローされたものなのでメッセージを返す
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse.fromDetails(error.message, error.details ?: emptyMap(), error.code)
                )
            }

            is ExecutionError.UnexpectedError -> {
                val errorMessage = error.cause?.message ?: error.message
                logger.error("UnexpectedError: {}", sanitizeForLog(errorMessage))
                respondInternalServerError(call)
            }
        }
    }

    /**
     * 認証エラーをHTTPレスポンスに変換する
     * セキュリティ: トークンの詳細やパーミッション名は漏洩させない
     *
     * @param call ApplicationCall
     * @param error 認証エラー
     */
    suspend fun respondAuthError(call: ApplicationCall, error: AuthError) {
        when (error) {
            is AuthError.NotAuthenticated ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Authentication required", code = "authentication_required")
                )

            is AuthError.InvalidToken -> {
                // 詳細はログにのみ出力
                logger.warn("InvalidToken: {}", sanitizeForLog(error.reason))
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Invalid token", code = "invalid_token")
                )
            }

            is AuthError.WrongTokenType -> {
                logger.warn("WrongTokenType: allowed={}", error.allowed)
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("This endpoint does not accept this token type", code = "wrong_token_type")
                )
            }

            is AuthError.PermissionDenied -> {
                // パーミッション名はログにのみ出力
                logger.warn("PermissionDenied: {}", sanitizeForLog(error.permission))
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Permission denied", code = "access_denied")
                )
            }

            is AuthError.PlayerOffline -> {
                // パーミッション評価不能はパーミッション不足と区別してクライアントに返す
                logger.warn("PlayerOffline: permission check skipped for {}", sanitizeForLog(error.permission))
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Player must be online for permission check", code = "player_offline")
                )
            }
        }
    }

    /**
     * パラメータ解決エラーをHTTPレスポンスに変換する
     * セキュリティ: 詳細な型情報や値はログにのみ出力
     *
     * @param call ApplicationCall
     * @param error 解決エラー
     */
    private suspend fun handleResolveError(call: ApplicationCall, error: ResolveError) {
        when (error) {
            is ResolveError.MissingPathParameter ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing required parameter: ${error.name}", code = "missing_parameter")
                )

            is ResolveError.MissingQueryParameter ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing required query parameter: ${error.name}", code = "missing_parameter")
                )

            is ResolveError.InvalidBodyFormat -> {
                // 詳細はログにのみ出力
                logger.warn("InvalidBodyFormat: {}", sanitizeForLog(error.cause.message))
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body format", code = "invalid_body")
                )
            }

            is ResolveError.AuthenticationRequired ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Authentication required", code = "authentication_required")
                )

            is ResolveError.TypeConversionFailed -> {
                // 詳細はログにのみ出力（サニタイズしてログ注入を防止）
                logger.warn(
                    "TypeConversionFailed: parameter={}, expected={}, actual={}",
                    sanitizeForLog(error.parameterName),
                    sanitizeForLog(error.expectedType),
                    sanitizeForLog(error.actualValue)
                )
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid parameter format", code = "invalid_parameter")
                )
            }

            is ResolveError.AccessDenied -> {
                // アクセス拒否理由はログにのみ出力
                logger.warn("AccessDenied: {}", sanitizeForLog(error.reason))
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Access denied", code = "access_denied")
                )
            }
        }
    }

    /**
     * 500エラーの共通レスポンス
     * セキュリティ: 内部エラーには常に汎用メッセージを返す
     *
     * @param call ApplicationCall
     */
    private suspend fun respondInternalServerError(call: ApplicationCall) {
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse("Internal server error", code = "internal_error")
        )
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RouteExecutor::class.java)

        /**
         * セキュリティ: ログ出力用に文字列をサニタイズする
         * 改行や制御文字を除去してログ注入攻撃を防止する
         *
         * @param input サニタイズする文字列
         * @return サニタイズされた文字列
         */
        fun sanitizeForLog(input: String?): String {
            if (input == null) return "null"
            // 改行、キャリッジリターン、タブ、その他の制御文字を除去または置換
            return input
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace(Regex("[\\x00-\\x1F\\x7F]"), "")
                .take(500) // 長すぎる入力を切り詰める
        }
    }
}
