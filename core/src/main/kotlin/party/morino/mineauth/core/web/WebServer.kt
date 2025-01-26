package party.morino.mineauth.core.web

import com.auth0.jwk.JwkProviderBuilder
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.velocity.*
import org.apache.velocity.runtime.RuntimeConstants
import org.apache.velocity.runtime.resource.loader.FileResourceLoader
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.get
import org.koin.java.KoinJavaComponent.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.core.file.data.WebServerConfigData
import party.morino.mineauth.core.utils.PlayerUtils.toOfflinePlayer
import party.morino.mineauth.core.utils.PlayerUtils.toUUID
import party.morino.mineauth.core.web.router.auth.AuthRouter.authRouter
import party.morino.mineauth.core.web.router.common.CommonRouter.commonRouter
import party.morino.mineauth.core.web.router.plugin.PluginRouter.pluginRouter
import java.security.KeyStore
import java.util.concurrent.TimeUnit

object WebServer : KoinComponent {
    private val plugin: MineAuth by inject()
    var originalServer: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine. Configuration>? = null
    fun settingServer() {
        val webServerConfigData: WebServerConfigData = get()
        plugin.logger.info("Setting up web server")
        val environment = applicationEnvironment {


        }
        originalServer = embeddedServer(Netty, environment = environment, configure = {
            connector {
                port = webServerConfigData.port
            }
            if (webServerConfigData.ssl!=null) {
                val keyStoreFile = plugin.dataFolder.resolve("keystore.jks")
                val keystore = KeyStore.getInstance(keyStoreFile, webServerConfigData.ssl.keyStorePassword.toCharArray())
                sslConnector(keyStore = keystore, keyAlias = webServerConfigData.ssl.keyAlias, keyStorePassword = { webServerConfigData.ssl.keyStorePassword.toCharArray() }, privateKeyPassword = { webServerConfigData.ssl.privateKeyPassword.toCharArray() }) {
                    port = webServerConfigData.ssl.sslPort
                    keyStorePath = keyStoreFile
                }
            }
        }, Application::module)
    }

    fun startServer() {
        originalServer?.start(wait = false)
    }

    fun stopServer() {
        originalServer?.stop(0, 0, TimeUnit.SECONDS)
    }
}

private fun Application.module() {
    val plugin: MineAuth by inject(MineAuth::class.java)
    val jwtConfigData: JWTConfigData = get(JWTConfigData::class.java)
    install(ContentNegotiation) {
        json()
    }

    install(Velocity) {
        setProperty(RuntimeConstants.RESOURCE_LOADERS, "file")
        setProperty("resource.loader.file.class", FileResourceLoader::class.java.name)
        setProperty("resource.loader.file.path", plugin.dataFolder.resolve("templates").absolutePath)
    }
    val jwkProvider = JwkProviderBuilder(jwtConfigData.issuer).cached(10, 24, TimeUnit.HOURS).rateLimited(10, 1, TimeUnit.MINUTES).build()
    install(Authentication) {
        jwt(JwtCompleteCode.USER_TOKEN.code) {
            realm = jwtConfigData.realm
            verifier(jwkProvider, jwtConfigData.issuer) {
                acceptLeeway(3)
            }

            validate { credential ->
                val clientId = credential.payload.getClaim("client_id").asString()
                val folder = plugin.dataFolder.resolve("clients").resolve(clientId).resolve("data.json")
                if (folder.exists()) {
                    JWTPrincipal(credential.payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(HttpStatusCode.Unauthorized, "Token is not valid or has expired")
            }
        }
    }

    routing {
        route("/") {
            get {
                call.respondText("Hello World!")
            }
        }
        staticFiles("assets", plugin.dataFolder.resolve("assets"))

        authRouter()
        route("/api/v1/commons") {
            commonRouter()
        }
        route("/api/v1/plugins") {
            pluginRouter()
        }

        authenticate("user-oauth-token") {
            get("/hello") {
                val principal = call.principal<JWTPrincipal>()
                val uuid = principal!!.payload.getClaim("playerUniqueId").asString()
                val expiresAt = principal.expiresAt?.time?.minus(System.currentTimeMillis())
                call.respondText("Hello, ${
                    uuid.toUUID().toOfflinePlayer().name
                }! Token is expired at $expiresAt ms.")
            }
        }
    }
}
