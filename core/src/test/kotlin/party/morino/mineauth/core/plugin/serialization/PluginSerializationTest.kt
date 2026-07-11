package party.morino.mineauth.core.plugin.serialization

import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import kotlin.reflect.full.starProjectedType

/**
 * クラスローダ分裂下での直列化を検証するテスト
 *
 * [IsolatingClassLoader] で `kotlinx.serialization.*` と DTO を再ロードすることで、
 * 利用側プラグインが serialization を shade（relocate なし）した状況を実際に再現する。
 * これにより「同一クラスローダ内では成功するが本番では失敗する」という見せかけの
 * テストを避け、バグの再現と修正の有効性の両方を実証する。
 */
class PluginSerializationTest {

    private val dtoFqName = SampleResponseDto::class.qualifiedName!!

    /**
     * 利用側プラグインのクラスローダーを模した分離クラスローダー
     *
     * `kotlinx.serialization.*` と DTO を child-first で自前ロードし、それ以外
     * （`kotlin.*` / `java.*` 等）は親に委譲する。これにより利用側の
     * `kotlinx.serialization.KSerializer` が親（＝MineAuth 側）とは別 Class になる。
     */
    private class IsolatingClassLoader(
        parent: ClassLoader,
        private val isolatedPrefix: String
    ) : ClassLoader(parent) {

        // DTO 本体だけでなく生成される `$Companion` / `$$serializer` も同じローダーで
        // 定義するため、DTO の完全修飾名を接頭辞として一致判定する。
        private fun shouldIsolate(name: String): Boolean =
            name.startsWith("kotlinx.serialization.") || name.startsWith(isolatedPrefix)

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!shouldIsolate(name)) return super.loadClass(name, resolve)

            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let {
                    if (resolve) resolveClass(it)
                    return it
                }
                // 親からクラスバイト列を取得し、このクラスローダーで再定義する
                val resourcePath = name.replace('.', '/') + ".class"
                val bytes = parent.getResourceAsStream(resourcePath)?.use { it.readBytes() }
                    ?: throw ClassNotFoundException(name)
                val clazz = defineClass(name, bytes, 0, bytes.size)
                if (resolve) resolveClass(clazz)
                return clazz
            }
        }
    }

    /** List<T> 用の最小限の ParameterizedType 実装 */
    private class ListType(private val elementType: Type) : ParameterizedType {
        override fun getActualTypeArguments(): Array<Type> = arrayOf(elementType)
        override fun getRawType(): Type = List::class.java
        override fun getOwnerType(): Type? = null
    }

    private fun newIsolatingClassLoader(): IsolatingClassLoader =
        IsolatingClassLoader(javaClass.classLoader, dtoFqName)

    @Test
    @DisplayName("Split classloader reproduces the serializer-not-found bug")
    fun reproducesBug() {
        val isolatedDto = newIsolatingClassLoader().loadClass(dtoFqName)

        // 分離クラスローダーで生成された DTO の KSerializer は親の KSerializer とは別 Class。
        // MineAuth 側（＝親クラスローダー）の serializer(kType) はこれをキャストできず失敗する。
        val kType = isolatedDto.kotlin.starProjectedType
        assertThrows<SerializationException> {
            serializer(kType)
        }
    }

    @Test
    @DisplayName("Classloader-aware encode succeeds under split classloader")
    fun encodesUnderSplitClassLoader() {
        val cl = newIsolatingClassLoader()
        val isolatedDto = cl.loadClass(dtoFqName)
        val instance = isolatedDto
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("hello", 42)

        // 利用側クラスローダーで直列化 → 分裂に非依存で成功する
        val jsonText = PluginSerialization.encodeToString(cl, isolatedDto, instance)

        assertEquals("""{"name":"hello","count":42}""", jsonText)
    }

    @Test
    @DisplayName("Classloader-aware encode handles generic List responses")
    fun encodesGenericList() {
        val cl = newIsolatingClassLoader()
        val isolatedDto = cl.loadClass(dtoFqName)
        val ctor = isolatedDto.getConstructor(String::class.java, Int::class.javaPrimitiveType)
        val list = listOf(ctor.newInstance("a", 1), ctor.newInstance("b", 2))

        // ジェネリクスを保持した java.lang.reflect.Type を渡して List<DTO> を直列化する
        val jsonText = PluginSerialization.encodeToString(cl, ListType(isolatedDto), list)

        assertEquals("""[{"name":"a","count":1},{"name":"b","count":2}]""", jsonText)
    }

    @Test
    @DisplayName("Classloader-aware decode round-trips under split classloader")
    fun decodesUnderSplitClassLoader() {
        val cl = newIsolatingClassLoader()
        val isolatedDto = cl.loadClass(dtoFqName)

        val decoded = PluginSerialization.decodeFromString(
            cl, isolatedDto, """{"name":"world","count":7}"""
        )

        // デコード結果は分離クラスローダーで生成されたインスタンスになる
        assertTrue(isolatedDto.isInstance(decoded))
        assertEquals("world", isolatedDto.getMethod("getName").invoke(decoded))
        assertEquals(7, isolatedDto.getMethod("getCount").invoke(decoded))
    }

    @Test
    @DisplayName("isSerializable detects resolvable and unresolvable types")
    fun detectsSerializable() {
        val cl = newIsolatingClassLoader()
        val isolatedDto = cl.loadClass(dtoFqName)

        assertTrue(PluginSerialization.isSerializable(cl, isolatedDto))
        // Object は @Serializable でないため解決できない
        assertTrue(!PluginSerialization.isSerializable(cl, Any::class.java))
    }
}
