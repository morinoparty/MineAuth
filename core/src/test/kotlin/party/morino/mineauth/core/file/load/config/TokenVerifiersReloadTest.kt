package party.morino.mineauth.core.file.load.config

import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.JWTVerifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JwtConfig
import party.morino.mineauth.core.file.data.MineAuthConfig
import party.morino.mineauth.core.file.data.PasswordConfig
import party.morino.mineauth.core.file.data.ServerConfig
import party.morino.mineauth.core.file.utils.JwtProvider
import party.morino.mineauth.core.file.utils.JwtTestSupport
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.utils.json
import java.io.File
import java.util.logging.Logger
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * /mineauth reload 後のトークン検証のテスト
 * 実際のConfigLoaderで読み直すと、Verifierが新しいissuerとtrustedIssuersに追従することを確認する
 */
class TokenVerifiersReloadTest {
    @TempDir
    lateinit var dataFolder: File

    private val firstIssuer = "https://first.example.com"
    private val secondIssuer = "https://second.example.com"
    private val trustedIssuer = "https://lobby.example.com"

    @BeforeEach
    fun setUp() {
        val plugin = mockk<MineAuth>(relaxed = true)
        every { plugin.logger } returns Logger.getLogger("TokenVerifiersReloadTest")
        every { plugin.dataFolder } returns dataFolder
        startKoin {
            modules(module { single { plugin } })
        }
        // 署名鍵はKeyUtilsのJVM全体のキャッシュではなくテスト用の鍵を使う
        mockkObject(JwtProvider)
        every { JwtProvider.algorithm } returns JwtTestSupport.algorithm
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(JwtProvider)
        stopKoin()
    }

    /**
     * config.jsonを書き込み、/mineauth reload と同じくConfigLoaderで読み直す
     */
    private fun loadConfig(baseUrl: String, trustedIssuers: List<String> = emptyList()) {
        val config = MineAuthConfig(
            server = ServerConfig(baseUrl = baseUrl),
            jwt = JwtConfig(trustedIssuers = trustedIssuers),
            password = PasswordConfig(pepper = "pepper"),
        )
        dataFolder.resolve("config.json").writeText(json.encodeToString(config))
        ConfigLoader().load()
    }

    private fun JWTVerifier.acceptsIssuer(issuer: String, kind: TokenKind = TokenKind.ACCESS): Boolean = try {
        verify(JwtTestSupport.sign(kind) { withIssuer(issuer) })
        true
    } catch (e: JWTVerificationException) {
        false
    }

    @Test
    @DisplayName("After reload the verifiers accept the new issuer and reject the removed issuers")
    fun verifiersFollowReload() {
        loadConfig(firstIssuer, trustedIssuers = listOf(trustedIssuer))
        // 再読み込み前に一度使っても、Verifierを保持し続けないことを確かめる
        assertTrue(JwtProvider.bearerVerifier.acceptsIssuer(firstIssuer))
        assertTrue(JwtProvider.bearerVerifier.acceptsIssuer(trustedIssuer))

        loadConfig(secondIssuer)

        assertTrue(JwtProvider.bearerVerifier.acceptsIssuer(secondIssuer))
        assertFalse(JwtProvider.bearerVerifier.acceptsIssuer(firstIssuer))
        assertFalse(JwtProvider.bearerVerifier.acceptsIssuer(trustedIssuer))
        // 全てのVerifierが同じ登録から作られるので、Bearer以外も新しいissuerに揃う
        assertTrue(JwtProvider.refreshTokenVerifier.acceptsIssuer(secondIssuer, TokenKind.REFRESH))
        assertTrue(JwtProvider.introspectionVerifier.acceptsIssuer(secondIssuer))
    }

    @Test
    @DisplayName("A rejected config keeps the previous verifiers")
    fun rejectedConfigKeepsPreviousVerifiers() {
        loadConfig(firstIssuer)

        assertThrows<IllegalStateException> { loadConfig("https://second.example.com?tenant=a") }

        assertTrue(JwtProvider.bearerVerifier.acceptsIssuer(firstIssuer))
    }
}
