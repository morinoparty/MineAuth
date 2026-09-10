package party.morino.mineauth.core.plugin.execution

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.CancellationException
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.intrinsics.intercepted
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * suspend関数を実行するハンドラー
 * Cloud (Incendo/cloud) の KotlinMethodCommandExecutionHandler パターンに基づく
 *
 * 動的プロキシを使用して、異なるクラスローダー間のContinuation互換性問題を解決する
 * MineAuthのプラグイン環境では、アドオンが独自のクラスローダーでロードされるため、
 * 標準のcallSuspendは使用できない
 *
 * ただしアドオンがMineAuthと同じKotlinランタイム（同じ`Continuation`クラス）を参照している場合は
 * 橋渡しが不要なので、プロキシもリフレクションも使わない高速パスで呼び出す
 */
class SuspendMethodExecutionHandler : MethodExecutionHandler {

    override suspend fun execute(
        metadata: EndpointMetadata,
        resolvedParams: List<Any?>
    ): Either<ExecutionError, Any?> {
        // Javaメソッドは登録単位で解決・アクセス可能化済み（nullになりえるのでチェックする）
        val javaMethod = metadata.javaMethod
            ?: return ExecutionError.MethodNotFound(metadata.method.name).left()

        return try {
            val result = invokeSuspendMethod(javaMethod, metadata, resolvedParams)
            result.right()
        } catch (e: CancellationException) {
            // コルーチンのキャンセル（クライアント切断等）は500に変換せず伝播させる
            throw e
        } catch (e: HttpError) {
            // HttpErrorは専用のエラー型に変換する
            ExecutionError.HttpErrorThrown(
                status = e.status.code,
                message = e.message,
                code = e.code,
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
     *
     * ハンドラーの`Continuation`がMineAuth本体と同一クラスなら[DetachedContinuation]を直接渡し、
     * 異なるクラスローダー由来なら動的プロキシで互換性問題を解決する。
     * どちらの経路でもハンドラーから見えるコンテキストは`EmptyCoroutineContext`で統一する
     * （実際の実行コンテキストはアドオン側で`withContext`等により指定される）。
     *
     * @param javaMethod 呼び出すJavaメソッド
     * @param metadata エンドポイントメタデータ（ハンドラーインスタンスと登録時解決済みの橋渡し情報）
     * @param params 解決済みパラメータ
     * @return メソッドの戻り値
     */
    private suspend fun invokeSuspendMethod(
        javaMethod: Method,
        metadata: EndpointMetadata,
        params: List<Any?>
    ): Any? {
        // suspendCoroutineUninterceptedOrReturn を使って継続を明示的に制御する
        return suspendCoroutineUninterceptedOrReturn { cont ->
            // intercepted()でディスパッチャを経由させ、再開後のKtorレスポンス処理が
            // アドオンの再開スレッド（例: Minecraftメインスレッド）上で走らないようにする
            val intercepted = cont.intercepted()

            // ハンドラー側のContinuationクラスは登録単位で解決済み
            val addonContinuationClass = metadata.continuationClass ?: javaMethod.parameterTypes.last()
            val continuationArg: Any = if (addonContinuationClass == Continuation::class.java) {
                // 高速パス: 同じKotlinランタイムなのでプロキシもリフレクションも不要
                DetachedContinuation(intercepted)
            } else {
                // 低速パス: 動的プロキシで異なるクラスローダー間のContinuation互換性を吸収する
                createContinuationProxy(addonContinuationClass, metadata.handlerEmptyCoroutineContext, intercepted)
            }

            // suspend関数はContinuationを最後の引数として受け取る
            // InvocationTargetExceptionはここで剥がさずexecute()側に伝播させ、
            // ハンドラー由来の例外（HttpError等）とリフレクション自体の失敗を区別して分類する
            val args = arrayOfNulls<Any?>(params.size + 1)
            for (index in params.indices) args[index] = params[index]
            args[params.size] = continuationArg
            val result = javaMethod.invoke(metadata.handlerInstance, *args)

            // COROUTINE_SUSPENDED の場合はそのまま返す（コルーチンがサスペンド中）
            if (isCoroutineSuspended(result)) COROUTINE_SUSPENDED else result
        }
    }

    /**
     * 同一クラスローダー用のContinuation
     *
     * プロキシ経路と同じく、ハンドラーには`EmptyCoroutineContext`を見せつつ、
     * 再開はディスパッチャを経由した元のContinuationへ転送する。
     *
     * @property delegate 再開先のContinuation（`intercepted()`済み）
     */
    private class DetachedContinuation(
        private val delegate: Continuation<Any?>
    ) : Continuation<Any?> {
        override val context: CoroutineContext
            get() = EmptyCoroutineContext

        override fun resumeWith(result: Result<Any?>) = delegate.resumeWith(result)
    }

    /**
     * アドオンクラスローダーから見えるContinuationを生成する
     * 動的プロキシを使用して、異なるクラスローダー間の互換性を確保する
     *
     * @param addonContinuationClass アドオン側のContinuationインターフェース
     * @param emptyContext アドオン側の`EmptyCoroutineContext`（登録単位で解決済み、未解決ならここで解決する）
     * @param original 元のContinuation
     * @return 互換性調整済みのContinuation
     */
    private fun createContinuationProxy(
        addonContinuationClass: Class<*>,
        emptyContext: Any?,
        original: Continuation<Any?>
    ): Any {
        // アドオン側のクラスローダーのEmptyCoroutineContextを使う
        // MineAuth側のCoroutineContextを返すとClassLoaderの互換性問題が発生するため
        val addonClassLoader = addonContinuationClass.classLoader
        val context = emptyContext ?: getEmptyCoroutineContext(addonClassLoader)

        return Proxy.newProxyInstance(
            addonClassLoader,
            arrayOf(addonContinuationClass)
        ) { _, method, args ->
            when (method.name) {
                // Result型が異なるクラスローダーなので反射で中身を取り出す
                "resumeWith" -> handleResumeWith(original, args?.get(0))
                // アドオン側のEmptyCoroutineContextを返す
                // 実際の実行コンテキストはアドオン側でwithContext等で指定される
                "getContext" -> context
                else -> null
            }
        }
    }

    /**
     * 指定されたクラスローダーからEmptyCoroutineContextを取得する
     * ClassLoader間の互換性問題を回避するため、アドオン側のコンテキストを使用する
     *
     * @param classLoader アドオンのクラスローダー
     * @return アドオン側のEmptyCoroutineContext
     */
    private fun getEmptyCoroutineContext(classLoader: ClassLoader): Any {
        val emptyContextClass = classLoader.loadClass("kotlin.coroutines.EmptyCoroutineContext")
        return emptyContextClass.getField("INSTANCE").get(null)
    }

    /**
     * resumeWithの引数を安全に変換してContinuationへ伝搬する
     * 異なるクラスローダー間でResult型を変換する
     *
     * Kotlinの Result は inline value class なので：
     * - 成功時で値がnullでない場合：値そのものが渡される（ボックス化されない）
     * - 成功時で値がnullの場合：Result.success(null)がボックス化される
     * - 失敗時：Result.failure(exception)がボックス化される（内部クラス Result$Failure）
     *
     * @param original 元のContinuation
     * @param resultArg アドオン側のResult または直接の値
     */
    private fun handleResumeWith(original: Continuation<Any?>, resultArg: Any?) {
        val className = resultArg?.javaClass?.name ?: ""

        when {
            // Result$Failure: inline value classの展開により内部のFailureオブジェクトが直接渡される場合
            // Failureクラスにはexceptionフィールドのみ存在し、getOrNull等のメソッドはない
            className.startsWith("kotlin.Result\$") -> {
                val exceptionField = resultArg!!.javaClass.getDeclaredField("exception")
                exceptionField.isAccessible = true
                val exception = exceptionField.get(resultArg) as Throwable
                original.resumeWith(Result.failure(exception))
            }
            // ボックス化されたResult型: getOrNull/exceptionOrNullメソッドが利用可能
            className == "kotlin.Result" -> {
                val value = resultArg?.javaClass?.getMethod("getOrNull")?.invoke(resultArg)
                val exception = resultArg?.javaClass?.getMethod("exceptionOrNull")?.invoke(resultArg) as? Throwable
                if (exception != null) {
                    original.resumeWith(Result.failure(exception))
                } else {
                    original.resumeWith(Result.success(value))
                }
            }
            // inline value classがボックス化されずに値が直接渡された場合（成功として扱う）
            else -> {
                original.resumeWith(Result.success(resultArg))
            }
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
                code = targetException.code,
                details = targetException.details
            ).left()
        } else {
            ExecutionError.InvocationFailed(targetException).left()
        }
    }
}
