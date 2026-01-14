package party.morino.mineauth.core.plugin.annotation

import arrow.core.getOrElse
import org.bukkit.entity.Player
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import party.morino.mineauth.api.annotations.*
import party.morino.mineauth.core.MineAuthTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AnnotationProcessorのユニットテスト
 */
@ExtendWith(MineAuthTest::class)
class AnnotationProcessorTest {

    private val processor = AnnotationProcessor()

    // テスト用のサンプルハンドラークラス
    class SimpleHandler {
        @GetMapping("/test")
        suspend fun getTest(@Param("id") id: String): String = id
    }

    class MultiMethodHandler {
        @GetMapping("/get")
        suspend fun get(@Param("id") id: String): String = id

        @PostMapping("/post")
        suspend fun post(@RequestBody body: String): String = body

        @PutMapping("/put")
        suspend fun put(@Param("id") id: String, @RequestBody body: String): String = "$id:$body"

        @DeleteMapping("/delete")
        suspend fun delete(@Param("id") id: String): Unit {}
    }

    class AuthenticatedHandler {
        @GetMapping("/protected")
        suspend fun protectedEndpoint(@AuthedAccessUser player: Player): String = player.name
    }

    @Permission("global.permission")
    class PermissionHandler {
        @GetMapping("/global")
        suspend fun globalPermission(@Param("id") id: String): String = id

        @GetMapping("/override")
        @Permission("method.permission")
        suspend fun overridePermission(@Param("id") id: String): String = id
    }

    class NoMappingHandler {
        fun notAnEndpoint(): String = "not an endpoint"
    }

    @Nested
    @DisplayName("Basic annotation processing")
    inner class BasicProcessing {

        @Test
        @DisplayName("Process simple handler with GET mapping")
        fun processSimpleHandler() {
            val handler = SimpleHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            assertEquals(1, endpoints.size)

            val endpoint = endpoints.first()
            assertEquals("/test", endpoint.path)
            assertEquals(HttpMethodType.GET, endpoint.httpMethod)
            assertTrue(endpoint.isSuspending)
            assertFalse(endpoint.requiresAuthentication)
            assertNull(endpoint.requiredPermission)
        }

        @Test
        @DisplayName("Process handler with multiple HTTP methods")
        fun processMultiMethodHandler() {
            val handler = MultiMethodHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            assertEquals(4, endpoints.size)

            val methods = endpoints.map { it.httpMethod }.toSet()
            assertTrue(methods.contains(HttpMethodType.GET))
            assertTrue(methods.contains(HttpMethodType.POST))
            assertTrue(methods.contains(HttpMethodType.PUT))
            assertTrue(methods.contains(HttpMethodType.DELETE))
        }

        @Test
        @DisplayName("Handler without mappings returns empty list")
        fun processNoMappingHandler() {
            val handler = NoMappingHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            assertTrue(endpoints.isEmpty())
        }
    }

    @Nested
    @DisplayName("Authentication handling")
    inner class AuthenticationHandling {

        @Test
        @DisplayName("Handler with @AuthedAccessUser requires authentication")
        fun authenticatedHandlerRequiresAuth() {
            val handler = AuthenticatedHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            assertEquals(1, endpoints.size)

            val endpoint = endpoints.first()
            assertTrue(endpoint.requiresAuthentication)
        }

        @Test
        @DisplayName("Handler without auth annotation does not require authentication")
        fun simpleHandlerDoesNotRequireAuth() {
            val handler = SimpleHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            val endpoint = endpoints.first()
            assertFalse(endpoint.requiresAuthentication)
        }
    }

    @Nested
    @DisplayName("Permission handling")
    inner class PermissionHandling {

        @Test
        @DisplayName("Class-level permission applies to methods")
        fun classLevelPermission() {
            val handler = PermissionHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }

            val globalEndpoint = endpoints.find { it.path == "/global" }
            assertEquals("global.permission", globalEndpoint?.requiredPermission)
        }

        @Test
        @DisplayName("Method-level permission overrides class-level")
        fun methodLevelPermissionOverrides() {
            val handler = PermissionHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }

            val overrideEndpoint = endpoints.find { it.path == "/override" }
            assertEquals("method.permission", overrideEndpoint?.requiredPermission)
        }
    }

    @Nested
    @DisplayName("Parameter handling")
    inner class ParameterHandling {

        @Test
        @DisplayName("@Param creates PathParam info")
        fun paramCreatesPathParam() {
            val handler = SimpleHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            val endpoint = endpoints.first()

            assertEquals(1, endpoint.parameters.size)
            val param = endpoint.parameters.first()
            assertTrue(param is ParameterInfo.PathParam)
            assertEquals(listOf("id"), (param as ParameterInfo.PathParam).names)
        }

        @Test
        @DisplayName("@RequestBody creates Body info")
        fun requestBodyCreatesBodyParam() {
            val handler = MultiMethodHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            val postEndpoint = endpoints.find { it.httpMethod == HttpMethodType.POST }

            val param = postEndpoint?.parameters?.first()
            assertTrue(param is ParameterInfo.Body)
        }

        @Test
        @DisplayName("@AuthedAccessUser creates AuthenticatedPlayer info")
        fun authedAccessUserCreatesAuthPlayer() {
            val handler = AuthenticatedHandler()
            val result = processor.process(handler)

            assertTrue(result.isRight())
            val endpoints = result.getOrElse { emptyList() }
            val endpoint = endpoints.first()

            val param = endpoint.parameters.first()
            assertTrue(param is ParameterInfo.AuthenticatedPlayer)
        }
    }
}
