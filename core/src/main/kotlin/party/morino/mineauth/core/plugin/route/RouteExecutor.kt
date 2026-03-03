package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import kotlinx.serialization.json.JsonPrimitive
import org.koin.core.component.KoinComponent
import org.slf4j.LoggerFactory
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
                    ErrorResponse.fromDetails(error.message, error.details ?: emptyMap())
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
    private suspend fun handleAuthError(call: ApplicationCall, error: AuthError) {
        when (error) {
            is AuthError.NotAuthenticated ->
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Authentication required"))

            is AuthError.InvalidToken -> {
                // 詳細はログにのみ出力
                logger.warn("InvalidToken: {}", sanitizeForLog(error.reason))
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid token"))
            }

            is AuthError.PermissionDenied -> {
                // パーミッション名はログにのみ出力
                logger.warn("PermissionDenied: {}", sanitizeForLog(error.permission))
                call.respond(HttpStatusCode.Forbidden, ErrorResponse("Permission denied"))
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
                    ErrorResponse("Missing required parameter: ${error.name}")
                )

            is ResolveError.InvalidBodyFormat -> {
                // 詳細はログにのみ出力
                logger.warn("InvalidBodyFormat: {}", sanitizeForLog(error.cause.message))
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid request body format")
                )
            }

            is ResolveError.AuthenticationRequired ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ErrorResponse("Authentication required")
                )

            is ResolveError.PlayerNotFound -> {
                // UUIDはログにのみ出力
                logger.warn("PlayerNotFound: {}", sanitizeForLog(error.uuid))
                call.respond(
                    HttpStatusCode.NotFound,
                    ErrorResponse("Player not found")
                )
            }

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
                    ErrorResponse("Invalid parameter format")
                )
            }

            is ResolveError.AccessDenied -> {
                // アクセス拒否理由はログにのみ出力
                logger.warn("AccessDenied: {}", sanitizeForLog(error.reason))
                call.respond(
                    HttpStatusCode.Forbidden,
                    ErrorResponse("Access denied")
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
        call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Internal server error"))
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
