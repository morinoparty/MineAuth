package party.morino.mineauth.core.openapi.generator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.reflect.full.starProjectedType

/**
 * 利用側プラグインがserializationをshadeしたDTOに対するスキーマ生成のテスト（issue #378関連の監査）
 *
 * 従来は`hasAnnotation<Serializable>()`がMineAuth本体の`Serializable` Classに対する
 * instanceof判定だったため、別クラスローダの`@Serializable`を認識できず、対象DTOのスキーマが
 * プロパティ無しの空オブジェクトに黙って縮退していた。FQN判定への変更でこれを解消する。
 */
class SchemaGeneratorClassLoaderTest {

    private val dtoFqName = "party.morino.mineauth.core.plugin.serialization.SampleResponseDto"

    /** `kotlinx.serialization.*` とDTOを child-first で再ロードする分離クラスローダー */
    private class IsolatingClassLoader(
        parent: ClassLoader,
        private val isolatedPrefix: String
    ) : ClassLoader(parent) {
        private fun shouldIsolate(name: String): Boolean =
            name.startsWith("kotlinx.serialization.") || name.startsWith(isolatedPrefix)

        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (!shouldIsolate(name)) return super.loadClass(name, resolve)
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let {
                    if (resolve) resolveClass(it)
                    return it
                }
                val bytes = parent.getResourceAsStream(name.replace('.', '/') + ".class")
                    ?.use { it.readBytes() } ?: throw ClassNotFoundException(name)
                val clazz = defineClass(name, bytes, 0, bytes.size)
                if (resolve) resolveClass(clazz)
                return clazz
            }
        }
    }

    @Test
    @DisplayName("Shaded @Serializable DTO yields properties, not an empty object")
    fun shadedDtoSchemaHasProperties() {
        val cl = IsolatingClassLoader(javaClass.classLoader, dtoFqName)
        val isolatedDto = cl.loadClass(dtoFqName)

        val schema = SchemaGenerator().generateSchema(isolatedDto.kotlin.starProjectedType)

        assertEquals("object", schema.type)
        // 別クラスローダの@Serializableでもプロパティが正しく展開されること
        assertNotNull(schema.properties, "properties must not be null for a @Serializable DTO")
        assertTrue(schema.properties!!.containsKey("name"))
        assertTrue(schema.properties!!.containsKey("count"))
        // non-nullableプロパティは必須
        assertEquals(setOf("name", "count"), schema.required?.toSet())
    }

    @Test
    @DisplayName("Shaded @SerialName remaps the property name via reflective read")
    fun shadedSerialNameRemapped() {
        val serialNameDtoFq = "party.morino.mineauth.core.openapi.generator.SerialNameSampleDto"
        val cl = IsolatingClassLoader(javaClass.classLoader, serialNameDtoFq)
        val isolatedDto = cl.loadClass(serialNameDtoFq)

        val schema = SchemaGenerator().generateSchema(isolatedDto.kotlin.starProjectedType)

        // 別クラスローダの@SerialNameでもリフレクションで値を読み取り、名前がリマップされること
        assertNotNull(schema.properties)
        assertTrue(schema.properties!!.containsKey("display_name"), "must use @SerialName value")
        assertFalse(schema.properties!!.containsKey("displayName"), "must not use the Kotlin property name")
        assertTrue(schema.properties!!.containsKey("count"))
        assertEquals(setOf("display_name", "count"), schema.required?.toSet())
    }
}
