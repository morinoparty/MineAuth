package party.morino.mineauth.core.plugin.serialization

import kotlinx.serialization.Serializable
import kotlinx.serialization.serializerOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.ParameterizedType
import kotlin.reflect.full.declaredMemberFunctions

/**
 * [toResolvableJavaType] の検証
 *
 * suspend 関数の戻り値型は CPS 変換により `KType.javaType` が `Object` へ縮退する。
 * その場合でも実際の宣言型（ジェネリクス含む）へ解決できることを確認する。
 */
class KTypeJavaTypesTest {

    @Serializable
    data class SuspendDto(val name: String)

    /** suspend ハンドラーを模したサンプル（リフレクションで returnType を取得する） */
    @Suppress("unused", "RedundantSuspendModifier")
    private class SampleHandler {
        suspend fun plain(): SuspendDto = SuspendDto("a")
        suspend fun generic(): List<SuspendDto> = emptyList()
        fun nonSuspend(): SuspendDto = SuspendDto("a")
        suspend fun any(): Any = Unit
    }

    private fun returnTypeOf(name: String) =
        SampleHandler::class.declaredMemberFunctions.first { it.name == name }.returnType

    @Test
    @DisplayName("suspend 関数の戻り値型は javaType の Object 縮退を補正して実際の Class を返す")
    fun suspendPlainClass() {
        val type = returnTypeOf("plain").toResolvableJavaType()
        assertEquals(SuspendDto::class.java, type)
        // 補正後の Type でシリアライザが解決できる（登録時検証と同じ経路）
        assertNotNull(serializerOrNull(type))
    }

    @Test
    @DisplayName("suspend 関数のジェネリック戻り値型は型引数を保持した ParameterizedType を返す")
    fun suspendGeneric() {
        val type = returnTypeOf("generic").toResolvableJavaType()
        assertTrue(type is ParameterizedType)
        type as ParameterizedType
        assertEquals(List::class.java, type.rawType)
        assertEquals(SuspendDto::class.java, type.actualTypeArguments.single())
        assertNotNull(serializerOrNull(type))
    }

    @Test
    @DisplayName("非 suspend 関数は宣言由来の javaType をそのまま返す")
    fun nonSuspend() {
        assertEquals(SuspendDto::class.java, returnTypeOf("nonSuspend").toResolvableJavaType())
    }

    @Test
    @DisplayName("KType 自体が Any の場合は Object のまま（縮退ではない）")
    fun anyStaysObject() {
        assertEquals(java.lang.Object::class.java, returnTypeOf("any").toResolvableJavaType())
    }
}
