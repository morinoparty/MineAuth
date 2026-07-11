package party.morino.mineauth.core.web.telemetry

import arrow.core.Either
import io.ktor.client.HttpClient
import io.ktor.client.engine.java.Java
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.auth.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.jakarta.Jetty
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.plugin.annotation.EndpointAccess
import party.morino.mineauth.core.plugin.annotation.EndpointMetadata
import party.morino.mineauth.core.plugin.annotation.HttpMethodType
import party.morino.mineauth.core.plugin.annotation.PathSegment
import party.morino.mineauth.core.plugin.dispatch.NamespaceTable
import party.morino.mineauth.core.plugin.dispatch.PluginEndpointDispatcher
import party.morino.mineauth.core.plugin.execution.ExecutionError
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandler
import party.morino.mineauth.core.plugin.execution.MethodExecutionHandlerFactory
import party.morino.mineauth.core.plugin.route.AuthenticationHandler
import party.morino.mineauth.core.plugin.route.ParameterResolver
import party.morino.mineauth.core.plugin.route.RouteExecutor
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlin.reflect.full.starProjectedType

/**
 * 利用側プラグインがserializationをshadeした状況でのレスポンス直列化を、実Jettyエンジンで
 * end-to-end検証する統合テスト（issue #378）
 *
 * [IsolatingClassLoader]でDTOと`kotlinx.serialization.*`を再ロードし、生成シリアライザが
 * MineAuth本体とは別クラスローダの`KSerializer`を実装する状況を実際に再現する。
 * この状態で従来は「Serializer for class 'Xxx' is not found」がレスポンスパイプラインへ漏れ、
 * text/plainの500になっていた。修正後は利用側クラスローダで直列化され、正しいJSONが返る。
 */
class PluginResponseSerializationJettyTest {

    private val dtoFqName = "party.morino.mineauth.core.plugin.serialization.SampleResponseDto"

    /**
     * 利用側プラグインのクラスローダーを模した分離クラスローダー
     * `kotlinx.serialization.*` とDTOを child-first で自前ロードする。
     */
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

    /**
     * shadeされたDTOを返すエンドポイントを持つディスパッチャを構築する
     */
    private fun buildDispatcher(): PluginEndpointDispatcher {
        val cl = IsolatingClassLoader(javaClass.classLoader, dtoFqName)
        val isolatedDto = cl.loadClass(dtoFqName)
        // ハンドラーインスタンスと戻り値をどちらも分離クラスローダー由来にする
        val instance = isolatedDto
            .getConstructor(String::class.java, Int::class.javaPrimitiveType)
            .newInstance("hello", 42)

        val endpoint = EndpointMetadata(
            method = DummyHandler::handle,
            handlerInstance = instance, // classLoader が分離クラスローダーになる
            path = "/data",
            pathSegments = listOf(PathSegment.Literal("data")),
            httpMethod = HttpMethodType.GET,
            access = EndpointAccess.Public(null),
            parameters = emptyList(),
            isSuspending = false,
            responseType = isolatedDto.kotlin.starProjectedType, // 分離DTOのKType
            returnsEither = false,
            responseResolvableByCore = false // shade済みのため本体では解決不能→利用側クラスローダ経路
        )
        val executor = RouteExecutor(
            ParameterResolver(Json),
            object : MethodExecutionHandlerFactory {
                override fun createHandler(metadata: EndpointMetadata): MethodExecutionHandler =
                    object : MethodExecutionHandler {
                        override suspend fun execute(
                            metadata: EndpointMetadata,
                            resolvedParams: List<Any?>
                        ): Either<ExecutionError, Any?> = Either.Right(instance)
                    }
            }
        )
        return PluginEndpointDispatcher(executor, AuthenticationHandler()).apply {
            install("sample", NamespaceTable("SamplePlugin", "/api/v1/plugins/sample", listOf(endpoint)))
        }
    }

    private class DummyHandler {
        fun handle(): String = "unused"
    }

    @Test
    @DisplayName("Shaded DTO response serializes to JSON on real Jetty engine")
    fun shadedResponseSerializes() {
        val dispatcher = buildDispatcher()
        val server = embeddedServer(Jetty, port = 0) {
            install(Authentication) { bearer("test-auth") { authenticate { null } } }
            routing {
                authenticate("test-auth", strategy = AuthenticationStrategy.Optional) {
                    route("/api/v1/plugins/{namespace}/{path...}") {
                        handle { dispatcher.dispatch(call) }
                    }
                }
            }
        }
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val client = HttpClient(Java)
            val (status, body) = runBlocking {
                val response: HttpResponse = client.get("http://localhost:$port/api/v1/plugins/sample/data")
                response.status to response.bodyAsText()
            }
            client.close()

            // 分裂下でも500ではなく正しいJSONが返ること
            assertEquals(HttpStatusCode.OK, status)
            assertEquals("""{"name":"hello","count":42}""", body)
        } finally {
            server.stop(0, 0, TimeUnit.SECONDS)
        }
    }
}
