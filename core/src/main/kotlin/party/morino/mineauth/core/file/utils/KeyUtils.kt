package party.morino.mineauth.core.file.utils

import com.nimbusds.jose.jwk.JWKSet
import kotlinx.serialization.encodeToString
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
import org.koin.core.component.inject
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.core.file.data.JWTConfigData
import party.morino.mineauth.api.utils.json
import java.io.File
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.*

object KeyUtils: KoinComponent {
    private val plugin: MineAuth by inject()

    fun init() {
        generateKeyPair()
        generateCertificate(getKeys().first, getKeys().second)
        loadJWKs()
    }

    private fun generateKeyPair() {
        val privateKeyFile = plugin.dataFolder.resolve("privateKey.pem")
        val publicKeyFile = plugin.dataFolder.resolve("publicKey.pem")
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
        val file = plugin.dataFolder.resolve("certificate.pem")
        val writer = file.writer()
        PemWriter(writer).use { pemWriter ->
            pemWriter.writeObject(pemObject)
        }
    }

    private fun loadJWKs() {
        val certificateFile = plugin.dataFolder.resolve("certificate.pem")
        if (!certificateFile.exists()) {
            plugin.logger.warning("cert file not found.")
            return
        }
        val jwksFile = plugin.dataFolder.resolve("jwks.json")
        val randomKeyAlias = UUID.randomUUID()
        if (!jwksFile.exists()) {
            plugin.logger.warning("jwks file not found.")
            generateJWKs(jwksFile, randomKeyAlias)
        }
        val jwtConfigFile = plugin.dataFolder.resolve("load").resolve("jwt.json")
        if (!jwtConfigFile.exists()) {
            plugin.logger.warning("jwtConfig file not found.")
            generateJWTConfig(jwtConfigFile, randomKeyAlias)
        }
        val jwtConfigData: JWTConfigData = json.decodeFromString(jwtConfigFile.readText())
        loadKoinModules(module {
            single { jwtConfigData }
        })

    }

    private fun generateJWTConfig(jwtConfigFile: File, randomKeyAlias: UUID) {
        jwtConfigFile.parentFile.mkdirs()
        jwtConfigFile.createNewFile()
        val jwtConfigData = JWTConfigData(
            "https://api.example.com", "example.com", "privateKey.pem", randomKeyAlias
        )
        jwtConfigFile.writeText(json.encodeToString(jwtConfigData))
    }

    private fun generateJWKs(jwksFile: File, randomKeyAlias: UUID) {
        val certificateFile = plugin.dataFolder.resolve("certificate.pem")
        val (privateKey, _) = getKeys()

        val randomPassword = RandomStringUtils.randomAlphabetic(16)
        val keyStore = KeyStore.getInstance("JKS")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            randomKeyAlias.toString(), privateKey, randomPassword.toCharArray(), arrayOf(
                CertificateFactory.getInstance("X.509").generateCertificate(certificateFile.inputStream())
            )
        )

        val rsaKey =
            com.nimbusds.jose.jwk.RSAKey.load(keyStore, randomKeyAlias.toString(), randomPassword.toCharArray())
        val jwkSet = JWKSet(rsaKey)

        jwksFile.writeText( jwkSet.toString(true))
    }

    fun getKeys(): Pair<PrivateKey, PublicKey> {
        val privateKeyFile = plugin.dataFolder.resolve("privateKey.pem")
        val publicKeyFile = plugin.dataFolder.resolve("publicKey.pem")
        val privateKeyContent = privateKeyFile.readText().replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "").replace("\\s+".toRegex(), "")
        val publicKeyContent =
            publicKeyFile.readText().replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "")
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