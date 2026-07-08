package party.morino.mineauth.core.plugin.annotation

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.mineauth.api.RegistrationError
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.Caller
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.Path
import party.morino.mineauth.api.annotations.Public
import party.morino.mineauth.api.annotations.Query
import party.morino.mineauth.api.annotations.Route
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.core.MineAuthTest
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AnnotationProcessorのユニットテスト
 * 新API（@Get/@Public/@Authenticated等）を対象とする
 */
@ExtendWith(MineAuthTest::class)
class AnnotationProcessorTest {

    private val processor = AnnotationProcessor()

    // テスト用のサンプルハンドラークラス

    class ValidHandler {
        @Get("/items/{id}")
        @Public
        suspend fun getItem(@Path("id") id: String, @Query("limit") limit: Int?): String = id
    }

    class MissingAccessHandler {
        @Get("/x")
        suspend fun x(): String = "x"
    }

    class ConflictingAccessHandler {
        @Get("/x")
        @Public
        @Authenticated
        suspend fun x(): String = "x"
    }

    class MissingParamAnnotationHandler {
        @Get("/x")
        @Public
        suspend fun x(id: String): String = id
    }

    class UnsupportedPathTypeHandler {
        @Get("/items/{id}")
        @Public
        suspend fun getItem(@Path("id") id: List<String>): String = id.toString()
    }

    class PathMismatchHandler {
        @Get("/items")
        @Public
        suspend fun getItem(@Path("id") id: String): String = id
    }

    class CallerMismatchHandler {
        @Get("/x")
        @Public
        suspend fun x(@Caller caller: Principal): String = caller.toString()
    }

    @Route("/prefix")
    class RoutePrefixHandler {
        @Get("/items")
        @Public
        suspend fun items(): String = "ok"
    }

    class MultipleErrorsHandler {
        @Get("/a")
        suspend fun a(): String = "a"

        @Get("/b")
        suspend fun b(): String = "b"
    }

    class NoEndpointsHandler {
        fun notAnEndpoint(): String = "not an endpoint"
    }

    @Nested
    @DisplayName("Valid handler processing")
    inner class ValidProcessing {

        @Test
        @DisplayName("Valid handler with @Get, @Public and typed params produces correct metadata")
        fun processValidHandler() {
            val result = processor.process(ValidHandler())

            val endpoints = assertNotNull(result.getOrNull())
            assertEquals(1, endpoints.size)

            val endpoint = endpoints.first()
            assertEquals("/items/{id}", endpoint.path)
            assertEquals(HttpMethodType.GET, endpoint.httpMethod)
            assertTrue(endpoint.access is EndpointAccess.Public)
            assertTrue(endpoint.isSuspending)

            assertEquals(2, endpoint.parameters.size)
            val pathParam = endpoint.parameters[0]
            assertTrue(pathParam is ParameterInfo.PathParam)
            assertEquals("id", (pathParam as ParameterInfo.PathParam).name)

            val queryParam = endpoint.parameters[1]
            assertTrue(queryParam is ParameterInfo.QueryParam)
            assertEquals("limit", (queryParam as ParameterInfo.QueryParam).name)
            // Int?であるため省略可能として扱われる
            assertTrue(queryParam.optional)
        }

        @Test
        @DisplayName("Class-level @Route prefix is prepended to endpoint path")
        fun routePrefixIsApplied() {
            val result = processor.process(RoutePrefixHandler())

            val endpoints = assertNotNull(result.getOrNull())
            assertEquals(1, endpoints.size)
            assertEquals("/prefix/items", endpoints.first().path)
        }
    }

    @Nested
    @DisplayName("Access declaration validation")
    inner class AccessValidation {

        @Test
        @DisplayName("Missing @Public/@Authenticated yields MissingAccessDeclaration")
        fun missingAccessDeclaration() {
            val result = processor.process(MissingAccessHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.MissingAccessDeclaration })
        }

        @Test
        @DisplayName("Both @Public and @Authenticated yields ConflictingAccessDeclaration")
        fun conflictingAccessDeclaration() {
            val result = processor.process(ConflictingAccessHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.ConflictingAccessDeclaration })
        }
    }

    @Nested
    @DisplayName("Parameter validation")
    inner class ParameterValidation {

        @Test
        @DisplayName("Parameter without annotation yields MissingParameterAnnotation")
        fun missingParameterAnnotation() {
            val result = processor.process(MissingParamAnnotationHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.MissingParameterAnnotation })
        }

        @Test
        @DisplayName("@Path with unsupported type yields UnsupportedParameterType")
        fun unsupportedPathParameterType() {
            val result = processor.process(UnsupportedPathTypeHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.UnsupportedParameterType })
        }

        @Test
        @DisplayName("@Path name absent from route path yields PathParameterMismatch")
        fun pathParameterMismatch() {
            val result = processor.process(PathMismatchHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.PathParameterMismatch })
        }

        @Test
        @DisplayName("Non-nullable @Caller on @Public endpoint yields CallerNullabilityMismatch")
        fun callerNullabilityMismatch() {
            val result = processor.process(CallerMismatchHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.CallerNullabilityMismatch })
        }
    }

    @Nested
    @DisplayName("Error accumulation and edge cases")
    inner class ErrorAccumulation {

        @Test
        @DisplayName("Errors from multiple broken endpoints are accumulated")
        fun errorsAccumulateAcrossEndpoints() {
            val result = processor.process(MultipleErrorsHandler())

            val errors = assertNotNull(result.leftOrNull())
            val missingAccessErrors = errors.filterIsInstance<RegistrationError.MissingAccessDeclaration>()
            assertEquals(2, missingAccessErrors.size)
            assertTrue(missingAccessErrors.any { it.function == "a" })
            assertTrue(missingAccessErrors.any { it.function == "b" })
        }

        @Test
        @DisplayName("Handler with no endpoint methods yields NoEndpoints")
        fun noEndpointsInHandler() {
            val result = processor.process(NoEndpointsHandler())

            val errors = assertNotNull(result.leftOrNull())
            assertTrue(errors.any { it is RegistrationError.NoEndpoints })
            assertNull(result.getOrNull())
        }
    }
}
