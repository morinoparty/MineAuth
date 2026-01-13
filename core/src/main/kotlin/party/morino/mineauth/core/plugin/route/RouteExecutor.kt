package party.morino.mineauth.core.plugin.route

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import org.koin.core.component.KoinComponent
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import kotlin.reflect.full.callSuspend

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
        // 1. パーミッションチェック（認証が必要な場合）
        if (metadata.requiredPermission != null && metadata.requiresAuthentication) {
            authHandler.authenticateAndCheckPermission(call, metadata.requiredPermission).fold(
                { error -> return handleAuthError(call, error) },
                { /* OK, 認証成功 */ }
            )
        } else if (metadata.requiresAuthentication) {
            // パーミッションなしで認証のみ必要な場合
            authHandler.authenticate(call).fold(
                { error -> return handleAuthError(call, error) },
                { /* OK, 認証成功 */ }
            )
        }

        // 2. パラメータを解決
        val resolvedParams = mutableListOf<Any?>()
        for (paramInfo in metadata.parameters) {
            parameterResolver.resolve(call, paramInfo).fold(
                { error -> return handleResolveError(call, error) },
                { value -> resolvedParams.add(value) }
            )
        }

        // 3. メソッドを実行
        try {
            val result = if (metadata.isSuspending) {
                // suspending関数の場合
                metadata.method.callSuspend(metadata.handlerInstance, *resolvedParams.toTypedArray())
            } else {
                // 通常の関数の場合
                metadata.method.call(metadata.handlerInstance, *resolvedParams.toTypedArray())
            }

            // 4. レスポンスを返す
            handleResult(call, result)
        } catch (e: HttpError) {
            // HttpErrorはそのままレスポンス
            call.respond(
                HttpStatusCode.fromValue(e.status.code),
                mapOf("error" to e.message, "details" to e.details)
            )
        } catch (e: Exception) {
            // その他の例外は500エラー
            e.printStackTrace()
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (e.cause?.message ?: e.message ?: "Unknown error"))
            )
        }
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
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Authentication required"))

            is AuthError.InvalidToken ->
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid token: ${error.reason}"))

            is AuthError.PermissionDenied ->
                call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Permission denied: ${error.permission}"))
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
                    mapOf("error" to "Missing path parameter: ${error.name}")
                )

            is ResolveError.InvalidBodyFormat ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf("error" to "Invalid request body format", "details" to (error.cause.message ?: ""))
                )

            is ResolveError.AuthenticationRequired ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    mapOf("error" to error.message)
                )

            is ResolveError.PlayerNotFound ->
                call.respond(
                    HttpStatusCode.NotFound,
                    mapOf("error" to "Player not found: ${error.uuid}")
                )

            is ResolveError.TypeConversionFailed ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    mapOf(
                        "error" to "Type conversion failed",
                        "parameter" to error.parameterName,
                        "expectedType" to error.expectedType,
                        "actualValue" to (error.actualValue ?: "null")
                    )
                )
        }
    }
}
