package party.morino.mineauth.core.plugin.annotation

import arrow.core.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.RegistrationError
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.Public
import party.morino.mineauth.core.plugin.serialization.SampleResponseDto

/**
 * 戻り値型のシリアライズ可能性検証（[AnnotationProcessor.analyzeReturnType]）のテスト
 *
 * MockBukkitを必要としないハンドラー（@PlayerParam/@Callerなし）で
 * 登録時検証の主要な分岐を確認する。
 */
class ReturnTypeValidationTest {

    /** シリアライズ不可能な戻り値型（@Serializableでない） */
    class NotSerializable(val value: Int)

    private val processor = AnnotationProcessor()

    @Test
    @DisplayName("Serializable return type resolves via core runtime")
    fun serializableReturnType() {
        val handler = object {
            @Get("/ok")
            @Public
            fun serializable(): SampleResponseDto = SampleResponseDto("x", 1)
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Right)
        val endpoints = (result as Either.Right).value
        assertEquals(1, endpoints.size)
        // 本体で解決できるため標準経路（responseResolvableByCore = true）
        assertTrue(endpoints.first().responseResolvableByCore)
    }

    @Test
    @DisplayName("Non-serializable return type is rejected at registration")
    fun nonSerializableReturnType() {
        val handler = object {
            @Get("/bad")
            @Public
            fun bad(): NotSerializable = NotSerializable(1)
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Left)
        val errors = (result as Either.Left).value
        assertTrue(errors.any { it is RegistrationError.ReturnTypeNotSerializable })
    }

    @Test
    @DisplayName("Unit return type is accepted without serialization check")
    fun unitReturnType() {
        val handler = object {
            @Get("/unit")
            @Public
            fun returnsUnit() {
            }
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Right)
        assertEquals(1, (result as Either.Right).value.size)
    }
}
