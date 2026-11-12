package party.morino.mineauth.core.file.utils

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import java.security.cert.Certificate
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.interfaces.RSAPublicKey
import java.text.ParseException
import java.util.UUID

/**
 * 公開用ファイルの内容が署名鍵と揃っているかを判定する純粋関数群
 *
 * OIDC Core 1.0 Section 10.1 により、署名に使う秘密鍵に対応する公開鍵をJWK Setで公開しなければならない。
 * ファイルの読み書きは SigningKeyPublisher が担当し、ここでは文字列の内容だけを見る。
 */
object PublishedKeyInspector {

    /**
     * certificate.pem が署名鍵の公開鍵を持っているかを判定する
     *
     * @param certificatePem certificate.pem の内容（ファイルがない場合はnull）
     * @param publicKey 署名鍵に対応する公開鍵
     * @return 揃っていればRight、揃っていなければその理由
     */
    fun inspectCertificate(certificatePem: String?, publicKey: RSAPublicKey): Either<PublishedKeyIssue, Unit> = either {
        ensureNotNull(certificatePem) { PublishedKeyIssue.CERTIFICATE_MISSING }

        // 破損したファイルは再生成すれば直るため、起動を止めずに不一致として扱う
        val certificate = Either.catchOrThrow<CertificateException, Certificate> {
            CertificateFactory.getInstance("X.509").generateCertificate(certificatePem.byteInputStream())
        }.mapLeft { PublishedKeyIssue.CERTIFICATE_UNREADABLE }.bind()

        // 実装クラスが異なっても比較できるよう、RSAの公開パラメータ（nとe）で比較する
        val certificateKey = certificate.publicKey as? RSAPublicKey
        ensure(
            certificateKey != null &&
                certificateKey.modulus == publicKey.modulus &&
                certificateKey.publicExponent == publicKey.publicExponent
        ) { PublishedKeyIssue.CERTIFICATE_KEY_DIFFERS }
    }

    /**
     * jwks.json が署名鍵の公開鍵を設定中のkidで公開しているかを判定する
     *
     * RPはトークンのkidで検証鍵を選ぶため、同じkidの鍵がちょうど1つ必要になる。
     * kidの異なる鍵は検証に影響しないので、管理者が手で残した旧鍵があっても不一致にはしない。
     *
     * @param jwksJson jwks.json の内容（ファイルがない場合はnull）
     * @param publicKey 署名鍵に対応する公開鍵
     * @param keyId トークンのヘッダーに入れるkid
     * @return 揃っていればRight、揃っていなければその理由
     */
    fun inspectJwks(jwksJson: String?, publicKey: RSAPublicKey, keyId: UUID): Either<PublishedKeyIssue, Unit> = either {
        ensureNotNull(jwksJson) { PublishedKeyIssue.JWKS_MISSING }

        // 破損したファイルは再生成すれば直るため、起動を止めずに不一致として扱う
        val jwkSet = Either.catchOrThrow<ParseException, JWKSet> { JWKSet.parse(jwksJson) }
            .mapLeft { PublishedKeyIssue.JWKS_UNREADABLE }
            .bind()

        val publishedKey = jwkSet.keys
            .filterIsInstance<RSAKey>()
            .singleOrNull { it.keyID == keyId.toString() }
        ensureNotNull(publishedKey) { PublishedKeyIssue.JWKS_KEY_ID_NOT_FOUND }

        // 鍵の同一性はRSAの公開パラメータ（nとe）で判定する
        ensure(
            publishedKey.modulus.decodeToBigInteger() == publicKey.modulus &&
                publishedKey.publicExponent.decodeToBigInteger() == publicKey.publicExponent
        ) { PublishedKeyIssue.JWKS_KEY_DIFFERS }
    }
}
