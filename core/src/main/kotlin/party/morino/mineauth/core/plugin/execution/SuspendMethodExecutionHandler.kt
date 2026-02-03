package party.morino.mineauth.core.plugin.execution

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.reflect.jvm.javaMethod

/**
 * suspend関数を実行するハンドラー
 * Cloud (Incendo/cloud) の KotlinMethodCommandExecutionHandler パターンに基づく
 *
 * 動的プロキシを使用して、異なるクラスローダー間のContinuation互換性問題を解決する
 * MineAuthのプラグイン環境では、アドオンが独自のクラスローダーでロードされるため、
 * 標準のcallSuspendは使用できない
 */
class SuspendMethodExecutionHandler : MethodExecutionHandler {

    override suspend fun execute(
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ): Either<ExecutionError, Any?> {
        // Javaメソッドへのアクセスはnullになりえるのでチェックする
        val javaMethod = metadata.method.javaMethod
            ?: return ExecutionError.MethodNotFound(metadata.method.name).left()

        return try {
            val result = invokeSuspendMethod(javaMethod, metadata.handlerInstance, resolvedParams)
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
     * Suspend関数を呼び出す
     * 動的プロキシを使用して、異なるクラスローダー間のContinuation互換性問題を解決する
     *
     * @param javaMethod 呼び出すJavaメソッド
     * @param instance ハンドラーインスタンス
     * @param params 解決済みパラメータ
     * @return メソッドの戻り値
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun invokeSuspendMethod(
        javaMethod: Method,
        instance: Any,
        params: List<Any?>
    ): Any? {
        // アクセス可能に設定する
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
                val result = javaMethod.invoke(instance, *args)

                // COROUTINE_SUSPENDED の場合はそのまま返す（コルーチンがサスペンド中）
                if (isCoroutineSuspended(result)) COROUTINE_SUSPENDED else result
            } catch (e: InvocationTargetException) {
                // ラップされた例外を再スロー
                throw e.targetException
            }
        }
    }

    /**
     * アドオンクラスローダーから見えるContinuationを生成する
     * 動的プロキシを使用して、異なるクラスローダー間の互換性を確保する
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
     * 異なるクラスローダー間でResult型を変換する
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
