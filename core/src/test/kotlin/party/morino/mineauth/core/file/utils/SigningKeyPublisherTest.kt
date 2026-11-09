package party.morino.mineauth.core.file.utils

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.mocks.logging.RecordingLogHandler
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 公開用ファイルの同期のテスト
 * jwks.jsonとcertificate.pemが常に署名鍵とkidに揃い、揃っていれば書き換えられないことを確認する
 */
class SigningKeyPublisherTest {
    @TempDir
    lateinit var generatedDir: File

    private val logHandler = RecordingLogHandler()
    private val keyId = UUID.randomUUID()

    private val jwksFile: File get() = generatedDir.resolve("jwks.json")
    private val certificateFile: File get() = generatedDir.resolve("certificate.pem")

    /**
     * 指定したディレクトリの公開用ファイルを鍵ペアとkidに揃える
     */
    private fun publish(keyPair: KeyPair, keyId: UUID, directory: File = generatedDir) =
        SigningKeyPublisher(directory, logHandler.newLogger())
            .publish(keyPair.private as RSAPrivateKey, keyPair.public as RSAPublicKey, keyId)

    /**
     * 公開中のJWKと証明書が鍵ペアとkidに一致することを確認する
     */
    private fun assertPublished(keyPair: KeyPair, keyId: UUID) {
        val publicKey = keyPair.public as RSAPublicKey
        val jwk = assertNotNull(JWKSet.parse(jwksFile.readText()).getKeyByKeyId(keyId.toString())) as RSAKey
        assertEquals(publicKey.modulus, jwk.modulus.decodeToBigInteger())
        assertEquals(publicKey.publicExponent, jwk.publicExponent.decodeToBigInteger())
        // 配信されるファイルに秘密鍵が含まれてはならない
        assertFalse(jwk.isPrivate)
        assertTrue(PublishedKeyInspector.inspectCertificate(certificateFile.readText(), publicKey).isRight())
    }

    @Test
    @DisplayName("Replacing the key pair regenerates both files once and warns")
    fun replacedKeyPairRegenerates() {
        publish(firstKeyPair, keyId)

        assertTrue(publish(secondKeyPair, keyId).isRight())

        assertPublished(secondKeyPair, keyId)
        assertEquals(1, logHandler.warnings.size)
        assertTrue(logHandler.warnings.single().contains(PublishedKeyIssue.CERTIFICATE_KEY_DIFFERS.description))

        // 揃った後の起動では再生成も警告もしない
        publish(secondKeyPair, keyId)
        assertEquals(1, logHandler.warnings.size)
    }

    @Test
    @DisplayName("Changing keyId regenerates the JWK Set with the new kid")
    fun changedKeyIdRegenerates() {
        publish(firstKeyPair, keyId)
        val newKeyId = UUID.randomUUID()

        assertTrue(publish(firstKeyPair, newKeyId).isRight())

        assertPublished(firstKeyPair, newKeyId)
        assertTrue(logHandler.warnings.single().contains(PublishedKeyIssue.JWKS_KEY_ID_NOT_FOUND.description))
    }

    private companion object {
        // RSA鍵の生成は遅いので、テスト間で共有する
        val firstKeyPair: KeyPair = generateKeyPair()
        val secondKeyPair: KeyPair = generateKeyPair()

        fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
