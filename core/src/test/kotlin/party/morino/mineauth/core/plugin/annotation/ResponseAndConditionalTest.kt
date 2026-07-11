package party.morino.mineauth.core.plugin.annotation

import arrow.core.Either
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.api.RegistrationError
import party.morino.mineauth.api.annotations.Conditional
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.Public
import party.morino.mineauth.api.http.ConditionalRequest
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.Response
import party.morino.mineauth.core.plugin.serialization.SampleResponseDto
import kotlin.reflect.jvm.jvmErasure

/**
 * issue #375: Responseラッパーの戻り値解析と@Conditionalパラメータ検証のテスト
 */
class ResponseAndConditionalTest {

    private val processor = AnnotationProcessor()

    @Test
    @DisplayName("Response<T> return type unwraps to inner T")
    fun responseWrapperUnwrapped() {
        val handler = object {
            @Get("/config")
            @Public
            fun config(): Response<SampleResponseDto> = Response.of(SampleResponseDto("x", 1))
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Right)
        val endpoint = (result as Either.Right).value.single()
        assertTrue(endpoint.returnsResponse)
        assertFalse(endpoint.returnsEither)
        // responseTypeはラップ内側のDTOを指す
        assertEquals(SampleResponseDto::class, endpoint.responseType.jvmErasure)
        assertTrue(endpoint.responseResolvableByCore)
    }

    @Test
    @DisplayName("Either<HttpError, Response<T>> unwraps both layers")
    fun eitherOfResponseUnwrapped() {
        val handler = object {
            @Get("/shop")
            @Public
            fun shop(): Either<HttpError, Response<SampleResponseDto>> =
                Either.Right(Response.of(SampleResponseDto("y", 2)))
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Right)
        val endpoint = (result as Either.Right).value.single()
        assertTrue(endpoint.returnsResponse)
        assertTrue(endpoint.returnsEither)
        assertEquals(SampleResponseDto::class, endpoint.responseType.jvmErasure)
    }

    @Test
    @DisplayName("@Conditional ConditionalRequest parameter is accepted")
    fun conditionalParamAccepted() {
        val handler = object {
            @Get("/data")
            @Public
            fun data(@Conditional cond: ConditionalRequest): Response<SampleResponseDto> =
                Response.of(SampleResponseDto("z", 3))
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Right)
        val endpoint = (result as Either.Right).value.single()
        assertTrue(endpoint.parameters.any { it is ParameterInfo.Conditional })
    }

    @Test
    @DisplayName("@Conditional with wrong type is rejected")
    fun conditionalWrongTypeRejected() {
        val handler = object {
            @Get("/bad")
            @Public
            fun bad(@Conditional wrong: String): SampleResponseDto = SampleResponseDto("a", 0)
        }
        val result = processor.process(handler)

        assertTrue(result is Either.Left)
        assertTrue((result as Either.Left).value.any { it is RegistrationError.UnsupportedParameterType })
    }
}
