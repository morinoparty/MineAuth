package party.morino.mineauth.core.commands

import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.command.CommandSender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.koin.core.context.loadKoinModules
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.ObservabilityConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.file.load.FileUtils
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.file.utils.KeyUtils
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.plugin.pluginModule
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.web.WebServer
import party.morino.mineauth.core.web.router.auth.oauth.OAuthRouteTestBase
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 実際のJettyを動かして /mineauth reload を通すテスト
 * 再起動後のサーバーが、Koinに登録されている設定とVerifierで動くことを外からのHTTPで確認する
 *
 * 鍵とDatabaseConnectorはJVM全体で保持されるので、テスト間で影響し合わないようにここだけモックする。
 * テレメトリを無効にした設定を使うため、OpenTelemetry SDKとMockBukkitは初期化しない
 */
class ReloadCommandServerTest : OAuthRouteTestBase() {
    private val firstIssuer = "https://first.example.com"
    private val secondIssuer = "https://second.example.com"

    private val port = ServerSocket(0).use { it.localPort }
    private val httpClient = HttpClient.newHttpClient()

    private val messages = mutableListOf<String>()
    private val sender = mockk<CommandSender>(relaxed = true)

    @BeforeEach
    fun startServer() {
        loadKoinModules(pluginModule)
        every { sender.sendRichMessage(capture(messages)) } returns Unit

        mockkObject(JwtProvider, KeyUtils, FileUtils)
        every { JwtProvider.algorithm } returns JwtTestSupport.algorithm
        every { KeyUtils.init() } returns Unit
        every { FileUtils.settingDatabase() } returns Unit

        // /helloのレスポンスに使うプレイヤー名の解決だけをモックする
        val offlinePlayer = mockk<OfflinePlayer>()
        every { offlinePlayer.name } returns "Steve"
        mockkStatic(Bukkit::class)
        every { Bukkit.getOfflinePlayer(any<UUID>()) } returns offlinePlayer

        // プラグインの起動時と同じ順序で設定を読み込み、サーバーを立ち上げる
        writeConfig(firstIssuer)
        FileUtils.loadFiles()
        WebServer.settingServer()
        WebServer.startServer()
    }

    @AfterEach
    fun stopServer() {
        WebServer.stopServer()
        unmockkStatic(Bukkit::class)
        unmockkObject(JwtProvider, KeyUtils, FileUtils)
    }

    private fun configFile() = tempDir.resolve("config.json").toFile()

    private fun writeConfig(baseUrl: String) {
        val config = MineAuthConfig(
            server = ServerConfig(baseUrl = baseUrl, port = port),
            password = PasswordConfig(pepper = "pepper"),
            observability = ObservabilityConfig(enabled = false, metricsEnabled = false),
        )
        configFile().writeText(json.encodeToString(config))
    }

    private fun send(path: String, bearer: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path")).GET()
            .apply { bearer?.let { header("Authorization", "Bearer $it") } }
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun discoveredIssuer(): String =
        Json.parseToJsonElement(send("/.well-known/openid-configuration").body())
            .jsonObject.getValue("issuer").jsonPrimitive.content

    private fun accessToken(issuer: String, clientId: String): String = JwtTestSupport.sign(TokenKind.ACCESS) {
        withIssuer(issuer)
        withJWTId(UUID.randomUUID().toString())
        withAudience(clientId)
        withClaim("client_id", clientId)
        withClaim("playerUniqueId", "00000000-0000-0000-0000-000000000001")
        withClaim("scope", "openid")
    }

    @Test
    @DisplayName("The server survives a broken config and then follows a valid reload")
    fun serverSurvivesBrokenConfigAndFollowsReload() {
        val clientId = runBlocking { registerClient(ClientType.PUBLIC).client.clientId }
        assertEquals(firstIssuer, discoveredIssuer())

        // config.jsonを壊して再読み込みしても、サーバーは以前の設定で応答し続ける
        configFile().writeText("""{ "server": { "baseUrl": """)
        ReloadCommand().reload(sender)

        assertTrue(messages.any { "Reload failed" in it })
        assertEquals(firstIssuer, discoveredIssuer())
        assertEquals(200, send("/hello", accessToken(firstIssuer, clientId)).statusCode())

        // 正しい設定に直して再読み込みすると、issuerとトークンの検証の両方が新しい設定に揃う
        writeConfig(secondIssuer)
        ReloadCommand().reload(sender)

        assertEquals("<green>Reloaded</green>", messages.last())
        assertEquals(secondIssuer, discoveredIssuer())
        assertEquals(200, send("/hello", accessToken(secondIssuer, clientId)).statusCode())
        assertEquals(401, send("/hello", accessToken(firstIssuer, clientId)).statusCode())
    }
}
