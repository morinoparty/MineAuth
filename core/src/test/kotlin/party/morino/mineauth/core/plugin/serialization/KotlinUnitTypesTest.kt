package party.morino.mineauth.core.plugin.serialization

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.net.URLClassLoader

/**
 * クラスローダーに依存しないUnit判定（[isKotlinUnit] / [isKotlinUnitValue]）のテスト
 *
 * 別のKotlinランタイムを参照するアドオンを、親を持たない独立したクラスローダーで再現する。
 */
class KotlinUnitTypesTest {

    /** MineAuthとは別のクラスローダーでロードした `kotlin.Unit` */
    private fun foreignUnitClass(): Class<*> {
        val stdlib = Unit::class.java.protectionDomain.codeSource.location
        val loader = URLClassLoader(arrayOf(stdlib), null)
        return loader.loadClass("kotlin.Unit")
    }

    @Test
    @DisplayName("Unit from another class loader is recognized")
    fun foreignUnitIsRecognized() {
        val foreign = foreignUnitClass()
        // 前提: 同名でも別のClassである
        assertNotSame(Unit::class.java, foreign)

        assertTrue(foreign.kotlin.isKotlinUnit())
        assertTrue(foreign.kotlin.isKotlinUnitOrNothing())
        assertTrue(foreign.getField("INSTANCE").get(null).isKotlinUnitValue())
    }

    @Test
    @DisplayName("Non-Unit values are not recognized as Unit")
    fun nonUnitIsNotRecognized() {
        assertFalse(String::class.isKotlinUnit())
        assertFalse("kotlin.Unit".isKotlinUnitValue())
        assertFalse(null.isKotlinUnitValue())
    }
}
