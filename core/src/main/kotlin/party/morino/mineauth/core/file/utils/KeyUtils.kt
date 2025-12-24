package party.morino.mineauth.core.file.utils

import com.nimbusds.jose.jwk.JWKSet
import org.apache.commons.lang3.RandomStringUtils
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

/**
 * RSA鍵ペアとJWKsの管理ユーティリティ
 * 鍵の生成、証明書の作成、JWKsの生成を行う
 *
 * 生成されるファイルは plugins/MineAuth/generated/ に配置される
 */
object KeyUtils : KoinComponent {
    private val plugin: MineAuth by inject()

    // 生成ファイル用ディレクトリ
    val generatedDir: File
        get() = plugin.dataFolder.resolve("generated")

    fun init() {
        // generated ディレクトリを作成
        generatedDir.mkdirs()

        generateKeyPair()
        generateCertificate(getKeys().first, getKeys().second)
        loadJWKs()
    }

    private fun generateKeyPair() {
        val privateKeyFile = generatedDir.resolve("privateKey.pem")
        val publicKeyFile = generatedDir.resolve("publicKey.pem")
        if (privateKeyFile.exists() || publicKeyFile.exists()) {
            plugin.logger.warning("Key files already exist.")
            return
        }
        privateKeyFile.parentFile.mkdirs()
        publicKeyFile.parentFile.mkdirs()
        privateKeyFile.createNewFile()
        publicKeyFile.createNewFile()
        val keyPairGenerator = KeyPairGenerator.getInstance("RSA")
        keyPairGenerator.initialize(2048, SecureRandom())
        val keyPair = keyPairGenerator.generateKeyPair()

        JcaPEMWriter(privateKeyFile.writer()).use { pemWriter ->
            pemWriter.writeObject(
                PemObject("PRIVATE KEY", keyPair.private.encoded)
            )
        }

        JcaPEMWriter(publicKeyFile.writer()).use { pemWriter ->
            pemWriter.writeObject(
                PemObject("PUBLIC KEY", keyPair.public.encoded)
            )
        }
    }

    private fun generateCertificate(privateKey: PrivateKey, publicKey: PublicKey) {
        val certFile = generatedDir.resolve("certificate.pem")
        if (certFile.exists()) {
            return
        }

        val startDate = Date()
        val endDate = Date(System.currentTimeMillis() + 365L * 24L * 60L * 60L * 1000L) // 1 year validity
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val subjectDN = X500Name("CN=Test Certificate")
        val issuerDN = subjectDN // self-signed

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuerDN, serialNumber, startDate, endDate, subjectDN, publicKey
        )

        val contentSigner: ContentSigner = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(privateKey)

        val certHolder = certBuilder.build(contentSigner)
        val cert = JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(certHolder)

        val pemObject = PemObject("CERTIFICATE", cert.encoded)
        val writer = certFile.writer()
        PemWriter(writer).use { pemWriter ->
            pemWriter.writeObject(pemObject)
        }
    }

    private fun loadJWKs() {
        val certificateFile = generatedDir.resolve("certificate.pem")
        if (!certificateFile.exists()) {
            plugin.logger.warning("cert file not found.")
            return
        }

        val jwksFile = generatedDir.resolve("jwks.json")

        // JWTConfigData は ConfigLoader で既に登録済み
        val jwtConfigData: JWTConfigData = get()
        val keyId = jwtConfigData.keyId

        if (!jwksFile.exists()) {
            plugin.logger.info("jwks file not found. Generating...")
            generateJWKs(jwksFile, keyId)
        }
    }

    private fun generateJWKs(jwksFile: File, keyId: UUID) {
        val certificateFile = generatedDir.resolve("certificate.pem")
        val (privateKey, _) = getKeys()

        val randomPassword = RandomStringUtils.randomAlphabetic(16)
        val keyStore = KeyStore.getInstance("JKS")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            keyId.toString(),
            privateKey,
            randomPassword.toCharArray(),
            arrayOf(
                CertificateFactory.getInstance("X.509").generateCertificate(certificateFile.inputStream())
            )
        )

        val rsaKey = com.nimbusds.jose.jwk.RSAKey.load(keyStore, keyId.toString(), randomPassword.toCharArray())
        val jwkSet = JWKSet(rsaKey)

        jwksFile.writeText(jwkSet.toString(true))
    }

    fun getKeys(): Pair<PrivateKey, PublicKey> {
        val privateKeyFile = generatedDir.resolve("privateKey.pem")
        val publicKeyFile = generatedDir.resolve("publicKey.pem")
        val privateKeyContent = privateKeyFile.readText()
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val publicKeyContent = publicKeyFile.readText()
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s+".toRegex(), "")
        val keyFactory = KeyFactory.getInstance("RSA")
        val privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyContent)))
        val publicKey = keyFactory.generatePublic(
            X509EncodedKeySpec(
                Base64.getDecoder().decode(publicKeyContent)
            )
        )
        return Pair(privateKey, publicKey)
    }
}
