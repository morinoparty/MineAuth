package party.morino.mineauth.core.file.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import party.morino.mineauth.core.model.TokenKind
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * JWTを扱うテスト用のヘルパー
 *
 * KeyUtilsはプラグインのデータフォルダの鍵を読み、結果をJVM全体でキャッシュする。
 * テスト間で影響し合わないよう、ここでは鍵をメモリ上で生成してTokenVerifiersへ直接渡す
 */
internal object JwtTestSupport {
    const val ISSUER = "https://issuer.test"

    // RSA鍵の生成は遅いため、テスト全体で1つを共有する
    val algorithm: Algorithm by lazy { newAlgorithm() }

    val verifiers: TokenVerifiers by lazy { TokenVerifiers(algorithm, listOf(ISSUER)) }

    /**
     * 別の鍵を持つアルゴリズムを生成する
     *
     * @return 新しいRSA256アルゴリズム
     */
    fun newAlgorithm(): Algorithm {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        return Algorithm.RSA256(keyPair.public as RSAPublicKey, keyPair.private as RSAPrivateKey)
    }

    /**
     * 本番と同じビルダーで指定した種別のトークンを発行する
     *
     * @param kind トークンの種別
     * @param expiresIn 現在時刻から有効期限までの期間（負の値で期限切れ）
     * @param algorithm 署名に使うアルゴリズム
     * @param configure クレームの追加設定
     * @return 署名済みのJWT
     */
    fun sign(
        kind: TokenKind,
        expiresIn: Duration = Duration.ofMinutes(5),
        algorithm: Algorithm = this.algorithm,
        configure: JWTCreator.Builder.() -> Unit = {}
    ): String = JwtProvider.newToken(kind)
        .withIssuer(ISSUER)
        .withExpiresAt(Date.from(Instant.now().plus(expiresIn)))
        .apply(configure)
        .sign(algorithm)

    /**
     * 明示的なtypを導入する前の形式（typ: JWT）で旧トークンを発行する
     *
     * @param tokenType token_typeクレームの値
     * @param configure クレームの追加設定
     * @return 署名済みのJWT
     */
    fun signLegacy(tokenType: String, configure: JWTCreator.Builder.() -> Unit = {}): String = JWT.create()
        .withIssuer(ISSUER)
        .withExpiresAt(Date.from(Instant.now().plus(Duration.ofMinutes(5))))
        .withClaim(TokenKind.TOKEN_TYPE_CLAIM, tokenType)
        .apply(configure)
        .sign(algorithm)
}
