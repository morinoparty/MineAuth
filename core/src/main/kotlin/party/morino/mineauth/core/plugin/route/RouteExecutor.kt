package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaMethod

/**
 * ハンドラーメソッドを実行するクラス
 * パラメータ解決、認証チェック、メソッド実行、レスポンス生成を担当する
 */
class RouteExecutor(
    private val parameterResolver: ParameterResolver,
    private val authHandler: AuthenticationHandler
) : KoinComponent {

    /**
     * エンドポイントメタデータに基づいてハンドラーを実行する
     *
     * @param call ApplicationCall
     * @param metadata エンドポイントメタデータ
     */
    suspend fun execute(call: ApplicationCall, metadata: EndpointMetadata) {
        // 認証・認可が必要なら先にチェックする
        if (!authenticateIfNeeded(call, metadata)) {
            return
        }

        // パラメータ解決に失敗した場合はレスポンス済みで終了
        val resolvedParams = resolveParameters(call, metadata) ?: return

        // ハンドラーを実行して結果をレスポンスに変換する
        try {
            val result = if (metadata.isSuspending) {
                // suspend関数はContinuationを付けて呼び出す
                invokeSuspendMethod(metadata, resolvedParams)
            } else {
                // 通常の関数はそのまま呼び出す
                invokeMethod(metadata, resolvedParams)
            }

            // 戻り値をHTTPレスポンスに変換
            handleResult(call, result)
        } catch (e: HttpError) {
            // HttpErrorはそのままレスポンス
            respondHttpError(call, e)
        } catch (e: InvocationTargetException) {
            handleInvocationTargetException(call, e)
        } catch (e: IllegalArgumentException) {
            // リフレクションの引数型不一致エラー
            respondArgumentTypeMismatch(call, metadata, resolvedParams)
        } catch (e: Exception) {
            respondUnexpectedError(call, e)
        }
    }

    /**
     * 認証・認可の必要性を判定して実行する
     *
     * @param call ApplicationCall
     * @param metadata エンドポイントメタデータ
     * @return 認証に成功した場合true
     */
    private suspend fun authenticateIfNeeded(call: ApplicationCall, metadata: EndpointMetadata): Boolean {
        if (!metadata.requiresAuthentication) {
            return true
        }

        val authResult = if (metadata.requiredPermission != null) {
            // パーミッションチェックを含む認証
            authHandler.authenticateAndCheckPermission(call, metadata.requiredPermission)
        } else {
            // 認証のみ
            authHandler.authenticate(call)
        }

        return when (authResult) {
            is Either.Left -> {
                handleAuthError(call, authResult.value)
                false
            }
            is Either.Right -> true
        }
    }

    /**
     * パラメータを順序通りに解決する
     *
     * @param call ApplicationCall
     * @param metadata エンドポイントメタデータ
     * @return 解決結果、失敗時はnull
     */
    private suspend fun resolveParameters(
        call: ApplicationCall,
        metadata: EndpointMetadata
    ): List<Any?>? {
        val resolvedParams = mutableListOf<Any?>()
        for (paramInfo in metadata.parameters) {
            when (val result = parameterResolver.resolve(call, paramInfo)) {
                is Either.Left -> {
                    handleResolveError(call, result.value)
                    return null
                }
                is Either.Right -> resolvedParams.add(result.value)
            }
        }
        return resolvedParams
    }

    /**
     * 通常のメソッドをJavaリフレクションで呼び出す
     *
     * @param metadata エンドポイントメタデータ
     * @param params 解決済みパラメータ
     * @return メソッドの戻り値
     */
    private fun invokeMethod(metadata: EndpointMetadata, params: List<Any?>): Any? {
        val javaMethod = requireJavaMethod(metadata)
        return javaMethod.invoke(metadata.handlerInstance, *params.toTypedArray())
    }

    /**
     * Suspend関数を呼び出す
     * 動的プロキシを使用して、異なるクラスローダー間のContinuation互換性問題を解決する
     *
     * @param metadata エンドポイントメタデータ
     * @param params 解決済みパラメータ
     * @return メソッドの戻り値
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeSuspendMethod(metadata: EndpointMetadata, params: List<Any?>): Any? {
        val javaMethod = requireJavaMethod(metadata)

        javaMethod.isAccessible = true

        // suspendCoroutineUninterceptedOrReturn を使って継続を明示的に制御する
        return suspendCoroutineUninterceptedOrReturn { cont ->
            try {
                // アドオンのクラスローダーから見えるContinuationインターフェースを取得
                val addonContinuationClass = javaMethod.parameterTypes.last()

                // 動的プロキシで異なるクラスローダー間のContinuation互換性を吸収する
                val proxyContinuation = createContinuationProxy(addonContinuationClass, cont)

                // suspend関数はContinuationを最後の引数として受け取る
                val args = (params + proxyContinuation).toTypedArray()
                val result = javaMethod.invoke(metadata.handlerInstance, *args)

                // COROUTINE_SUSPENDED の場合はそのまま返す（コルーチンがサスペンド中）
                if (isCoroutineSuspended(result)) COROUTINE_SUSPENDED else result
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /**
     * KotlinリフレクションからJavaメソッドを取得する
     *
     * @param metadata エンドポイントメタデータ
     * @return JavaのMethod
     */
    private fun requireJavaMethod(metadata: EndpointMetadata): Method {
        return checkNotNull(metadata.method.javaMethod) {
            "Cannot get Java method for ${metadata.method.name}"
        }
    }

    /**
     * アドオンクラスローダーから見えるContinuationを生成する
     *
     * @param addonContinuationClass アドオン側のContinuationインターフェース
     * @param original 元のContinuation
     * @return 互換性調整済みのContinuation
     */
    private fun createContinuationProxy(
        addonContinuationClass: Class<*>,
        original: Continuation<Any?>
    ): Any {
        return Proxy.newProxyInstance(
            addonContinuationClass.classLoader,
            arrayOf(addonContinuationClass)
        ) { _, method, args ->
            when (method.name) {
                // Result型が異なるクラスローダーなので反射で中身を取り出す
                "resumeWith" -> handleResumeWith(original, args?.get(0))
                "getContext" -> original.context
                else -> null
            }
        }
    }

    /**
     * resumeWithの引数を安全に変換してContinuationへ伝搬する
     *
     * @param original 元のContinuation
     * @param resultArg アドオン側のResult
     */
    private fun handleResumeWith(original: Continuation<Any?>, resultArg: Any?) {
        val value = resultArg?.javaClass?.getMethod("getOrNull")?.invoke(resultArg)
        val exception = resultArg?.javaClass?.getMethod("exceptionOrNull")?.invoke(resultArg) as? Throwable
        if (exception != null) {
            original.resumeWith(Result.failure(exception))
        } else {
            original.resumeWith(Result.success(value))
        }
    }

    /**
     * サスペンド状態の判定（クラスローダー差異を考慮）
     *
     * @param result 実行結果
     * @return サスペンド中ならtrue
     */
    private fun isCoroutineSuspended(result: Any?): Boolean {
        return result === COROUTINE_SUSPENDED ||
            result?.javaClass?.name == "kotlin.coroutines.intrinsics.CoroutineSingletons"
    }

    /**
     * メソッドの戻り値をHTTPレスポンスに変換する
     *
     * @param call ApplicationCall
     * @param result メソッドの戻り値
     */
    private suspend fun handleResult(call: ApplicationCall, result: Any?) {
        when (result) {
            null -> call.respond(HttpStatusCode.NoContent)
            is Unit -> call.respond(HttpStatusCode.OK)
            else -> call.respond(result)
        }
    }

    /**
     * 認証エラーをHTTPレスポンスに変換する
     *
     * @param call ApplicationCall
     * @param error 認証エラー
     */
    private suspend fun handleAuthError(call: ApplicationCall, error: AuthError) {
        when (error) {
            is AuthError.NotAuthenticated ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))

            is AuthError.InvalidToken ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token: ${error.reason}"))

            is AuthError.PermissionDenied ->
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Permission denied: ${error.permission}"))
        }
    }

    /**
     * パラメータ解決エラーをHTTPレスポンスに変換する
     *
     * @param call ApplicationCall
     * @param error 解決エラー
     */
    private suspend fun handleResolveError(call: ApplicationCall, error: ResolveError) {
        when (error) {
            is ResolveError.MissingPathParameter ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Missing path parameter: ${error.name}")
                )

            is ResolveError.InvalidBodyFormat ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "Invalid request body format",
                        details = mapOf("details" to JsonPrimitive(error.cause.message ?: ""))
                    )
                )

            is ResolveError.AuthenticationRequired ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse(error.message)
                )

            is ResolveError.PlayerNotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Player not found: ${error.uuid}")
                )

            is ResolveError.TypeConversionFailed ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = "Type conversion failed",
                        details = mapOf(
                            "parameter" to JsonPrimitive(error.parameterName),
                            "expectedType" to JsonPrimitive(error.expectedType),
                            "actualValue" to JsonPrimitive(error.actualValue ?: "null")
                        )
                    )
                )
        }
    }

    /**
     * HttpErrorを共通レスポンスに変換する
     *
     * @param call ApplicationCall
     * @param error HttpError
     */
    private suspend fun respondHttpError(call: ApplicationCall, error: HttpError) {
        call.respond(
            HttpStatusCode.fromValue(error.status.code),
            ErrorResponse.fromDetails(error.message, error.details)
        )
    }

    /**
     * InvocationTargetExceptionを安全に処理する
     *
     * @param call ApplicationCall
     * @param error InvocationTargetException
     */
    private suspend fun handleInvocationTargetException(call: ApplicationCall, error: InvocationTargetException) {
        val targetException = error.targetException
        if (targetException is HttpError) {
            respondHttpError(call, targetException)
        } else {
            System.err.println(
                "[RouteExecutor] InvocationTargetException: ${targetException.javaClass.name}: ${targetException.message}"
            )
            respondInternalServerError(call, targetException.message ?: "Unknown error")
        }
    }

    /**
     * 引数型不一致エラーを整形して返す
     *
     * @param call ApplicationCall
     * @param metadata エンドポイントメタデータ
     * @param resolvedParams 解決済みパラメータ
     */
    private suspend fun respondArgumentTypeMismatch(
        call: ApplicationCall,
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ) {
        val expectedParams = metadata.method.parameters
            .filter { it.kind != KParameter.Kind.INSTANCE }
            .joinToString(", ") { "${it.name}: ${it.type}" }
        val actualParams = resolvedParams.mapIndexed { index, value ->
            "arg$index: ${value?.let { it::class.simpleName } ?: "null"}"
        }.joinToString(", ")
        System.err.println(
            "[RouteExecutor] IllegalArgumentException: Method=${metadata.method.name}, " +
                "Expected=[$expectedParams], Actual=[$actualParams]"
        )
        call.respond(
            HttpStatusCode.InternalServerError,
            ErrorResponse(
                error = "argument type mismatch",
                details = mapOf(
                    "method" to JsonPrimitive(metadata.method.name),
                    "expectedParams" to JsonPrimitive(expectedParams),
                    "actualParams" to JsonPrimitive(actualParams)
                )
            )
        )
    }

    /**
     * 予期しない例外を500として返す
     *
     * @param call ApplicationCall
     * @param error 例外
     */
    private suspend fun respondUnexpectedError(call: ApplicationCall, error: Exception) {
        val errorMessage = error.cause?.message ?: error.message ?: "Unknown error"
        System.err.println("[RouteExecutor] Exception: ${error.javaClass.name}: $errorMessage")
        respondInternalServerError(call, errorMessage)
    }

    /**
     * 500エラーの共通レスポンス
     *
     * @param call ApplicationCall
     * @param message エラーメッセージ
     */
    private suspend fun respondInternalServerError(call: ApplicationCall, message: String) {
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(message))
    }
}
