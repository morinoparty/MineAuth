package party.morino.mineauth.core.plugin.route

import arrow.core.Either
import arrow.core.left
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.PlayerAccess
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import party.morino.mineauth.api.http.fail
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.plugin.annotation.CallerKind
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.ParameterInfo
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.auth.ServicePrincipal
import party.morino.mineauth.core.plugin.execution.DefaultMethodExecutionHandlerFactory
import java.util.UUID
import kotlin.reflect.KFunction
import kotlin.reflect.typeOf

/**
 * RouteExecutorの認証・認可エラー応答のテスト
 * すべての401・403でWWW-Authenticateが返り、内部情報がクライアントへ漏れないことを確認する
 */
class RouteExecutorAuthErrorTest {

    @BeforeEach
    fun setUp() {
        startKoin {
            modules(module { single { JWTConfigData(realm = "MineAuth", keyId = UUID.randomUUID()) } })
        }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /** 指定した認証エラーを応答するルートへリクエストし、レスポンスを検証する */
    private fun respondingWith(error: AuthError, verify: suspend (HttpResponse) -> Unit) = testApplication {
        val executor = RouteExecutor(ParameterResolver(Json), DefaultMethodExecutionHandlerFactory())
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                get("/protected") { executor.respondAuthError(call, error) }
            }
        }
        verify(client.get("/protected"))
    }

    /** エンドポイントを実行するルートへリクエストし、レスポンスを検証する */
    private fun executing(
        metadata: EndpointMetadata,
        principal: Principal?,
        pathParams: Map<String, String> = emptyMap(),
        verify: suspend (HttpResponse) -> Unit
    ) = testApplication {
        val executor = RouteExecutor(ParameterResolver(Json), DefaultMethodExecutionHandlerFactory())
        application {
            install(ContentNegotiation) { json(Json) }
            routing {
                get("/protected") { executor.execute(RequestContext(call, principal, pathParams), metadata) }
            }
        }
        verify(client.get("/protected"))
    }

    /** ハンドラーのメソッドからエンドポイントメタデータを作る */
    private fun metadataOf(
        method: KFunction<*>,
        handler: Any,
        parameters: List<ParameterInfo> = emptyList(),
        returnsEither: Boolean = false
    ) = EndpointMetadata(
        method = method,
        handlerInstance = handler,
        path = "/protected",
        pathSegments = listOf(PathSegment.Literal("protected")),
        httpMethod = HttpMethodType.GET,
        access = EndpointAccess.Public(reason = null),
        parameters = parameters,
        isSuspending = false,
        responseType = typeOf<String>(),
        returnsEither = returnsEither,
        responseResolvableByCore = true,
        returnsResponse = false
    )

    /**
     * HttpErrorを投げる、または返すテスト用のハンドラー
     *
     * @property status 返すHTTPステータス
     * @property message エラーメッセージ
     */
    private class FailingHandler(private val status: HttpStatus, private val message: String = "Not your shop") {
        fun thrown(): String = fail(status, message, code = "shop_denied", details = mapOf("id" to "1"))

        fun returned(): Either<HttpError, String> = HttpError(status, message, code = "shop_denied").left()
    }

    /** ハンドラーが指定した本文のままであることを確認する */
    private suspend fun assertKeepsHandlerBody(response: HttpResponse) {
        val body = response.bodyAsText()
        assertTrue(body.contains("\"error\":\"Not your shop\""), body)
        assertTrue(body.contains("\"code\":\"shop_denied\""), body)
    }

    @Test
    @DisplayName("NotAuthenticated is 401 with a challenge without an error attribute")
    fun notAuthenticatedHasNoErrorAttribute() = respondingWith(AuthError.NotAuthenticated) { response ->
        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
        assertTrue(response.bodyAsText().contains("\"code\":\"authentication_required\""))
    }

    @Test
    @DisplayName("InvalidToken is 401 invalid_token and keeps the reason out of the response")
    fun invalidTokenKeepsReasonInLogs() = respondingWith(AuthError.InvalidToken("Unsupported token_type: x")) { response ->
        val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertTrue(header.contains("error=\"invalid_token\""), header)
        assertFalse(header.contains("Unsupported"), header)
        assertFalse(response.bodyAsText().contains("Unsupported"))
    }

    @Test
    @DisplayName("InsufficientScope is 403 and names the required scope")
    fun insufficientScopeNamesRequiredScope() = respondingWith(AuthError.InsufficientScope("plugin")) { response ->
        val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(header.contains("error=\"insufficient_scope\""), header)
        assertTrue(header.contains("scope=\"plugin\""), header)
        assertTrue(response.bodyAsText().contains("\"code\":\"insufficient_scope\""))
    }

    @Test
    @DisplayName("WrongTokenType is 403 insufficient_scope without a scope attribute")
    fun wrongTokenTypeHasNoScopeAttribute() = respondingWith(AuthError.WrongTokenType(setOf(CallerType.USER))) { response ->
        val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(header.contains("error=\"insufficient_scope\""), header)
        assertFalse(header.contains(" scope="), header)
        assertTrue(response.bodyAsText().contains("\"code\":\"wrong_token_type\""))
    }

    @Test
    @DisplayName("PermissionDenied is 403 insufficient_scope and does not leak the permission")
    fun permissionDeniedDoesNotLeakPermission() = respondingWith(AuthError.PermissionDenied("shop.secret")) { response ->
        val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(header.contains("error=\"insufficient_scope\""), header)
        assertFalse(header.contains(" scope="), header)
        assertFalse(header.contains("shop.secret"), header)
        assertFalse(response.bodyAsText().contains("shop.secret"))
    }

    @Nested
    @DisplayName("Parameter resolution errors")
    inner class ResolveErrors {

        private val handler = FailingHandler(HttpStatus.NOT_FOUND)

        @Test
        @DisplayName("AccessDenied is 403 insufficient_scope and keeps the reason out of the response")
        fun accessDeniedHasChallenge() = executing(
            // サービストークンでmeを指定すると、パラメータ解決の段階で拒否される
            metadataOf(handler::thrown, handler, listOf(ParameterInfo.TargetPlayer("player", PlayerAccess.SELF_ONLY))),
            ServicePrincipal(accountId = "service"),
            mapOf("player" to "me")
        ) { response ->
            val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(header.contains("error=\"insufficient_scope\""), header)
            assertFalse(header.contains(" scope="), header)
            assertFalse(header.contains("Service accounts"), header)
            assertTrue(response.bodyAsText().contains("\"code\":\"access_denied\""))
        }

        @Test
        @DisplayName("AuthenticationRequired is 401 with a challenge without an error attribute")
        fun authenticationRequiredHasChallenge() = executing(
            metadataOf(handler::thrown, handler, listOf(ParameterInfo.Caller(CallerKind.ANY, optional = false))),
            principal = null
        ) { response ->
            assertEquals(HttpStatusCode.Unauthorized, response.status)
            assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
            assertTrue(response.bodyAsText().contains("\"code\":\"authentication_required\""))
        }
    }

    @Nested
    @DisplayName("Errors from the handler")
    inner class HandlerErrors {

        private val verifiedCaller = ServicePrincipal(accountId = "service")

        @Test
        @DisplayName("Thrown 401 keeps its body and adds a challenge without an error attribute")
        fun thrownUnauthorized() {
            val handler = FailingHandler(HttpStatus.UNAUTHORIZED)

            executing(metadataOf(handler::thrown, handler), verifiedCaller) { response ->
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
                assertKeepsHandlerBody(response)
            }
        }

        @Test
        @DisplayName("Thrown 403 with a verified caller is insufficient_scope without a scope attribute")
        fun thrownForbiddenWithCaller() {
            val handler = FailingHandler(HttpStatus.FORBIDDEN)

            executing(metadataOf(handler::thrown, handler), verifiedCaller) { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals(
                    "Bearer realm=\"MineAuth\", error=\"insufficient_scope\", error_description=\"Not your shop\"",
                    response.headers[HttpHeaders.WWWAuthenticate]
                )
                assertKeepsHandlerBody(response)
            }
        }

        @Test
        @DisplayName("Thrown 403 without a caller has no error attribute")
        fun thrownForbiddenWithoutCaller() {
            // 公開エンドポイントの403はトークンの問題ではないので、insufficient_scopeとは伝えない
            val handler = FailingHandler(HttpStatus.FORBIDDEN)

            executing(metadataOf(handler::thrown, handler), principal = null) { response ->
                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
                assertKeepsHandlerBody(response)
            }
        }

        @Test
        @DisplayName("Left values get the same challenges as thrown errors")
        fun leftValues() {
            val unauthorized = FailingHandler(HttpStatus.UNAUTHORIZED)
            val forbidden = FailingHandler(HttpStatus.FORBIDDEN)

            executing(metadataOf(unauthorized::returned, unauthorized, returnsEither = true), verifiedCaller) { response ->
                assertEquals(HttpStatusCode.Unauthorized, response.status)
                assertEquals("Bearer realm=\"MineAuth\"", response.headers[HttpHeaders.WWWAuthenticate])
                assertKeepsHandlerBody(response)
            }
            executing(metadataOf(forbidden::returned, forbidden, returnsEither = true), verifiedCaller) { response ->
                val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

                assertEquals(HttpStatusCode.Forbidden, response.status)
                assertTrue(header.contains("error=\"insufficient_scope\""), header)
                assertKeepsHandlerBody(response)
            }
        }

        @Test
        @DisplayName("Other statuses have no challenge")
        fun otherStatuses() {
            val handler = FailingHandler(HttpStatus.NOT_FOUND)

            executing(metadataOf(handler::thrown, handler), verifiedCaller) { response ->
                assertEquals(HttpStatusCode.NotFound, response.status)
                assertNull(response.headers[HttpHeaders.WWWAuthenticate])
            }
        }

        @Test
        @DisplayName("Long handler message is shortened in the header but not in the body")
        fun longMessageIsShortenedInHeader() {
            val longMessage = "x".repeat(10_000)
            val handler = FailingHandler(HttpStatus.FORBIDDEN, longMessage)

            executing(metadataOf(handler::thrown, handler), verifiedCaller) { response ->
                val header = response.headers[HttpHeaders.WWWAuthenticate].orEmpty()

                assertTrue(header.length < 1_000, "header length: ${header.length}")
                assertTrue(response.bodyAsText().contains(longMessage))
            }
        }
    }
}
