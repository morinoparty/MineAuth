package party.morino.mineauth.core.plugin.execution

import arrow.core.Either
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.PathSegment
import kotlin.coroutines.Continuation
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf

/**
 * suspend関数の同一クラスローダー高速パス（プロキシ無し）の検証
 */
class SuspendMethodExecutionHandlerTest {

    private class Handler {
        suspend fun greet(name: String): String {
            delay(1)
            return "hi $name"
        }

        suspend fun failAfterSuspend(): String {
            delay(1)
            throw HttpError(HttpStatus.NOT_FOUND, "missing", code = "missing")
        }
    }

    private fun metadata(handler: Handler, function: KFunction<*>) = EndpointMetadata(
        method = function,
        handlerInstance = handler,
        path = "/x",
        pathSegments = listOf(PathSegment.Literal("x")),
        httpMethod = HttpMethodType.GET,
        access = EndpointAccess.Public(null),
        parameters = emptyList(),
        isSuspending = true,
        responseType = typeOf<String>(),
        returnsEither = false,
        responseResolvableByCore = true,
        returnsResponse = false
    )

    @Test
    @DisplayName("Same-classloader suspend handler resumes with its value")
    fun resumesWithValue() = runBlocking {
        val handler = Handler()
        val meta = metadata(handler, handler::greet)
        // テスト環境ではハンドラーと本体が同じKotlinランタイムを共有している＝高速パスの前提
        assertEquals(Continuation::class.java, meta.continuationClass)

        val result = SuspendMethodExecutionHandler().execute(meta, listOf("zunda"))
        assertEquals(Either.Right("hi zunda"), result)
    }

    @Test
    @DisplayName("HttpError thrown after suspension becomes HttpErrorThrown")
    fun httpErrorAfterSuspension() = runBlocking {
        val handler = Handler()
        val result = SuspendMethodExecutionHandler().execute(metadata(handler, handler::failAfterSuspend), emptyList())

        assertTrue(result is Either.Left)
        val error = (result as Either.Left).value
        assertTrue(error is ExecutionError.HttpErrorThrown)
        assertEquals(404, (error as ExecutionError.HttpErrorThrown).status)
        assertEquals("missing", error.code)
    }
}
