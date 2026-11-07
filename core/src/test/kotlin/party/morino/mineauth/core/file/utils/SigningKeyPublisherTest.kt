package party.morino.mineauth.core.file.utils

import arrow.core.left
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import party.morino.mineauth.core.file.data.ConfigError
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
    @DisplayName("First start generates both files without a warning")
    fun firstStartGeneratesFiles() {
        assertTrue(publish(firstKeyPair, keyId).isRight())

        assertPublished(firstKeyPair, keyId)
        assertEquals(emptyList(), logHandler.warnings)
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

    @Test
    @DisplayName("Matching files are left untouched on the next start")
    fun matchingFilesAreNotRewritten() {
        publish(firstKeyPair, keyId)
        // 書き換えられたら消える目印を付ける（再生成すると内容を丸ごと置き換える）
        jwksFile.appendText("\n")
        certificateFile.appendText("\n")
        val jwksBefore = jwksFile.readText()
        val certificateBefore = certificateFile.readText()

        publish(firstKeyPair, keyId)

        assertEquals(jwksBefore, jwksFile.readText())
        assertEquals(certificateBefore, certificateFile.readText())
        assertEquals(emptyList(), logHandler.warnings)
    }

    @Test
    @DisplayName("Servers sharing the key and keyId do not regenerate on each start")
    fun sharedKeyDoesNotRegenerate() {
        // もう一方のサーバーは鍵のコピー前に別の鍵で一度起動していた
        val otherServerDir = generatedDir.resolve("other").also { it.mkdirs() }
        publish(secondKeyPair, keyId, otherServerDir)
        publish(firstKeyPair, keyId)
        // 鍵のコピー後の初回起動で揃う
        publish(firstKeyPair, keyId, otherServerDir)
        assertEquals(1, logHandler.warnings.size)
        val jwksBefore = otherServerDir.resolve("jwks.json").readText()

        // 以降は両サーバーとも再生成しない（生成時刻が変わるので、再生成すれば内容も変わる）
        repeat(2) {
            publish(firstKeyPair, keyId)
            publish(firstKeyPair, keyId, otherServerDir)
        }

        assertEquals(jwksBefore, otherServerDir.resolve("jwks.json").readText())
        assertEquals(1, logHandler.warnings.size)
    }

    @Test
    @DisplayName("Corrupted files are regenerated")
    fun corruptedFilesAreRegenerated() {
        publish(firstKeyPair, keyId)
        jwksFile.writeText("""{"keys": [""")
        certificateFile.writeText("not a certificate")

        assertTrue(publish(firstKeyPair, keyId).isRight())

        assertPublished(firstKeyPair, keyId)
        val warning = logHandler.warnings.single()
        assertTrue(warning.contains(PublishedKeyIssue.JWKS_UNREADABLE.description))
        assertTrue(warning.contains(PublishedKeyIssue.CERTIFICATE_UNREADABLE.description))
    }

    @Test
    @DisplayName("Keys with another kid in the JWK Set are kept")
    fun otherKidIsKept() {
        publish(firstKeyPair, keyId)
        // 管理者が旧鍵を手で残している想定で、別のkidの鍵を追加する
        val retiredKey = RSAKey.Builder(secondKeyPair.public as RSAPublicKey).keyID("retired").build()
        val current = JWKSet.parse(jwksFile.readText()).keys
        jwksFile.writeText(JWKSet(current + retiredKey).toString(true))

        publish(firstKeyPair, keyId)

        assertNotNull(JWKSet.parse(jwksFile.readText()).getKeyByKeyId("retired"))
        assertEquals(emptyList(), logHandler.warnings)
    }

    @Test
    @DisplayName("A private key and public key from different pairs are rejected")
    fun mismatchedKeyPairIsRejected() {
        val mismatched = KeyPair(secondKeyPair.public, firstKeyPair.private)

        assertEquals(ConfigError.SigningKeyPairMismatch.left(), publish(mismatched, keyId))

        // 検証できない鍵を公開しない
        assertFalse(jwksFile.exists())
        assertFalse(certificateFile.exists())
    }

    private companion object {
        // RSA鍵の生成は遅いので、テスト間で共有する
        val firstKeyPair: KeyPair = generateKeyPair()
        val secondKeyPair: KeyPair = generateKeyPair()

        fun generateKeyPair(): KeyPair =
            KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }
}
