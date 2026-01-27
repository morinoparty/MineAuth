package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.execution.ExecutionError
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import kotlin.reflect.KParameter

/**
 * ハンドラーメソッドを実行するクラス
 * Cloud (Incendo/cloud) のパターンに基づきファクトリを使用してハンドラーを選択する
 *
 * パラメータ解決、認証チェック、メソッド実行、レスポンス生成を担当する
 */
class RouteExecutor(
    private val parameterResolver: ParameterResolver,
    private val authHandler: AuthenticationHandler,
    private val executionHandlerFactory: MethodExecutionHandlerFactory
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

        // ファクトリ経由でハンドラーを取得
        val handler = executionHandlerFactory.createHandler(metadata)

        // ハンドラーを実行して結果を処理
        when (val result = handler.execute(metadata, resolvedParams)) {
            is Either.Left -> handleExecutionError(call, result.value, metadata, resolvedParams)
            is Either.Right -> handleResult(call, result.value)
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
     * 実行エラーをHTTPレスポンスに変換する
     *
     * @param call ApplicationCall
     * @param error 実行エラー
     * @param metadata エンドポイントメタデータ（エラーメッセージ用）
     * @param resolvedParams 解決済みパラメータ（エラーメッセージ用）
     */
    private suspend fun handleExecutionError(
        call: ApplicationCall,
        error: ExecutionError,
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ) {
        when (error) {
            is ExecutionError.InvocationFailed -> {
                System.err.println(
                    "[RouteExecutor] InvocationFailed: ${error.cause.javaClass.name}: ${error.cause.message}"
                )
                respondInternalServerError(call, error.cause.message ?: "Unknown error")
            }

            is ExecutionError.MethodNotFound -> {
                System.err.println("[RouteExecutor] MethodNotFound: ${error.methodName}")
                respondInternalServerError(call, "Method not found: ${error.methodName}")
            }

            is ExecutionError.ArgumentTypeMismatch -> {
                val expectedParams = metadata.method.parameters
                    .filter { it.kind != KParameter.Kind.INSTANCE }
                    .joinToString(", ") { "${it.name}: ${it.type}" }
                val actualParams = resolvedParams.mapIndexed { index, value ->
                    "arg$index: ${value?.let { it::class.simpleName } ?: "null"}"
                }.joinToString(", ")
                System.err.println(
                    "[RouteExecutor] ArgumentTypeMismatch: Method=${metadata.method.name}, " +
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

            is ExecutionError.HttpErrorThrown -> {
                call.respond(
                    HttpStatusCode.fromValue(error.status),
                    ErrorResponse.fromDetails(error.message, error.details ?: emptyMap())
                )
            }

            is ExecutionError.UnexpectedError -> {
                val errorMessage = error.cause?.message ?: error.message
                System.err.println("[RouteExecutor] UnexpectedError: $errorMessage")
                respondInternalServerError(call, errorMessage)
            }
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
     * 500エラーの共通レスポンス
     *
     * @param call ApplicationCall
     * @param message エラーメッセージ
     */
    private suspend fun respondInternalServerError(call: ApplicationCall, message: String) {
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse(message))
    }
}
