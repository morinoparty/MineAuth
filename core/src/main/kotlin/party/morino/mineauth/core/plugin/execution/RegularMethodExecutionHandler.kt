package party.morino.mineauth.core.plugin.execution

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import java.lang.reflect.InvocationTargetException
import kotlin.reflect.jvm.javaMethod

/**
 * 通常の（non-suspend）関数を実行するハンドラー
 * Cloud (Incendo/cloud) の KotlinMethodCommandExecutionHandler パターンに基づく
 *
 * suspend関数ではない通常のメソッドを実行するためのハンドラー
 * Javaリフレクションを使用して直接メソッドを呼び出す
 */
class RegularMethodExecutionHandler : MethodExecutionHandler {

    override suspend fun execute(
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ): Either<ExecutionError, Any?> {
        // Javaメソッドへのアクセスはnullになりえるのでチェックする
        val javaMethod = metadata.method.javaMethod
            ?: return ExecutionError.MethodNotFound(metadata.method.name).left()

        return try {
            // 通常のメソッド呼び出し
            val result = javaMethod.invoke(metadata.handlerInstance, *resolvedParams.toTypedArray())
            result.right()
        } catch (e: HttpError) {
            // HttpErrorは専用のエラー型に変換する
            ExecutionError.HttpErrorThrown(
                status = e.status.code,
                message = e.message,
                details = e.details
            ).left()
        } catch (e: InvocationTargetException) {
            // ラップされた例外を取り出して処理する
            handleInvocationTargetException(e)
        } catch (e: IllegalArgumentException) {
            // 引数型の不一致エラー
            ExecutionError.ArgumentTypeMismatch(
                methodName = metadata.method.name,
                expectedTypes = javaMethod.parameterTypes.map { it.simpleName },
                actualTypes = resolvedParams.map { it?.javaClass?.simpleName ?: "null" }
            ).left()
        } catch (e: Exception) {
            ExecutionError.UnexpectedError(
                message = e.message ?: "Unknown error",
                cause = e
            ).left()
        }
    }

    /**
     * InvocationTargetExceptionを解析してExecutionErrorに変換する
     *
     * @param e InvocationTargetException
     * @return 変換されたExecutionError
     */
    private fun handleInvocationTargetException(e: InvocationTargetException): Either<ExecutionError, Nothing> {
        val targetException = e.targetException
        return if (targetException is HttpError) {
            ExecutionError.HttpErrorThrown(
                status = targetException.status.code,
                message = targetException.message,
                details = targetException.details
            ).left()
        } else {
            ExecutionError.InvocationFailed(targetException).left()
        }
    }
}
