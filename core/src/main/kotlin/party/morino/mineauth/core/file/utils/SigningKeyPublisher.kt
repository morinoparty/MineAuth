package party.morino.mineauth.core.file.utils

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.ContentSigner
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.util.io.pem.PemObject
import org.bouncycastle.util.io.pem.PemWriter
import party.morino.mineauth.core.file.data.ConfigError
import java.io.File
import java.math.BigInteger
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import java.util.UUID
import java.util.logging.Logger

/**
 * 公開用ファイル（certificate.pem / jwks.json）を署名鍵に揃えるクラス
 *
 * jwks_uriはjwks.jsonをそのまま配信するため、ここで揃えておけばRPに渡る鍵とkidはトークンの署名と一致する。
 * KeyUtilsはキャッシュを持つobjectなので、鍵の差し替えをテストできるよう処理をこのクラスへ分離している。
 *
 * @property generatedDir 公開用ファイルを置くディレクトリ
 * @property logger 再生成を管理者へ知らせるロガー
 */
class SigningKeyPublisher(
    private val generatedDir: File,
    private val logger: Logger
) {
    private val certificateFile: File get() = generatedDir.resolve("certificate.pem")
    private val jwksFile: File get() = generatedDir.resolve("jwks.json")

    /**
     * 公開用ファイルが署名鍵とkidに揃っているかを確認し、揃っていなければ両方を作り直す
     *
     * 揃っている場合はファイルに触れない。そのため鍵とkidを共有する複数サーバーでも起動のたびに再生成されない。
     *
     * @param privateKey 署名に使う秘密鍵
     * @param publicKey 検証に使う公開鍵
     * @param keyId トークンのヘッダーに入れるkid
     * @return 成功時はRight。秘密鍵と公開鍵が鍵ペアでない場合はエラー
     */
    fun publish(privateKey: RSAPrivateKey, publicKey: RSAPublicKey, keyId: UUID): Either<ConfigError, Unit> = either {
        // 片方だけ差し替えられていると、どちらのファイルを公開しても署名を検証できない
        ensure(privateKey.modulus == publicKey.modulus) { ConfigError.SigningKeyPairMismatch }

        val issues = listOf(
            PublishedKeyInspector.inspectCertificate(readIfExists(certificateFile), publicKey),
            PublishedKeyInspector.inspectJwks(readIfExists(jwksFile), publicKey, keyId),
        ).mapNotNull { it.leftOrNull() }

        if (issues.isEmpty()) return@either

        logRegeneration(issues)
        // 証明書のx5cをJWKに含めるため、証明書とJWK Setは常に同じ鍵から作り直す
        val certificate = generateCertificate(privateKey, publicKey)
        writeCertificate(certificate)
        writeJwks(certificate, keyId)
    }

    /**
     * ファイルが存在すれば内容を読む
     *
     * @param file 読み込むファイル
     * @return ファイルの内容（存在しない場合はnull）
     */
    private fun readIfExists(file: File): String? = if (file.exists()) file.readText() else null

    /**
     * 再生成の理由をログに出す
     * ファイルがないだけなら初回起動の正常な動作なのでinfo、内容の不一致は運用上の変更なのでwarningにする
     *
     * @param issues 揃っていない理由の一覧
     */
    private fun logRegeneration(issues: List<PublishedKeyIssue>) {
        val reasons = issues.joinToString("; ") { it.description }
        val isFirstGeneration = issues.all {
            it == PublishedKeyIssue.CERTIFICATE_MISSING || it == PublishedKeyIssue.JWKS_MISSING
        }
        if (isFirstGeneration) {
            logger.info("Generating certificate.pem and jwks.json ($reasons).")
        } else {
            logger.warning(
                "The published signing key does not match the key in use ($reasons). " +
                    "Regenerating certificate.pem and jwks.json from the current key and jwt.keyId."
            )
        }
    }

    /**
     * 署名鍵から自己署名証明書を作る
     *
     * @param privateKey 証明書の署名に使う秘密鍵
     * @param publicKey 証明書に載せる公開鍵
     * @return 自己署名証明書
     */
    private fun generateCertificate(privateKey: RSAPrivateKey, publicKey: RSAPublicKey): X509Certificate {
        val startDate = Date()
        val endDate = Date(System.currentTimeMillis() + CERTIFICATE_VALIDITY_MILLIS)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val subjectDN = X500Name("CN=Test Certificate")
        val issuerDN = subjectDN // self-signed

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            issuerDN, serialNumber, startDate, endDate, subjectDN, publicKey
        )
        val contentSigner: ContentSigner = JcaContentSignerBuilder("SHA256WithRSAEncryption").build(privateKey)
        val certHolder = certBuilder.build(contentSigner)
        return JcaX509CertificateConverter().setProvider(BouncyCastleProvider()).getCertificate(certHolder)
    }

    /**
     * 証明書をPEM形式でcertificate.pemへ書き込む
     *
     * @param certificate 書き込む証明書
     */
    private fun writeCertificate(certificate: X509Certificate) {
        PemWriter(certificateFile.writer()).use { pemWriter ->
            pemWriter.writeObject(PemObject("CERTIFICATE", certificate.encoded))
        }
    }

    /**
     * 証明書からJWK Setを作ってjwks.jsonへ書き込む
     *
     * @param certificate JWKの元になる証明書
     * @param keyId JWKのkid
     */
    private fun writeJwks(certificate: X509Certificate, keyId: UUID) {
        // parseはkidに証明書のシリアル番号を入れるので、トークンのヘッダーと同じkidへ置き換える
        val rsaKey = RSAKey.Builder(RSAKey.parse(certificate)).keyID(keyId.toString()).build()
        // 公開鍵だけを書き出す（証明書から作ったJWKに秘密鍵は含まれないが、配信されるファイルなので明示する）
        jwksFile.writeText(JWKSet(rsaKey).toString(true))
    }

    private companion object {
        // 自己署名証明書の有効期間（1年）
        const val CERTIFICATE_VALIDITY_MILLIS = 365L * 24L * 60L * 60L * 1000L
    }
}
