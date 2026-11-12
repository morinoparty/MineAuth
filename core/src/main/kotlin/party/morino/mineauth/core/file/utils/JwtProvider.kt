package party.morino.mineauth.core.file.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTCreator
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.model.TokenKind
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * JWT署名・検証に使用するAlgorithmとVerifierを提供するユーティリティ
 *
 * Algorithmは初回アクセス時にKeyUtilsのキャッシュ済み鍵ペアから構築する
 * Verifierは種別ごとに分かれており、全種別を受け付ける汎用のVerifierは意図的に公開しない
 */
object JwtProvider : KoinComponent {

    /**
     * RSA256署名アルゴリズム（キャッシュ済み鍵ペアから構築）
     * トークン発行時の署名と検証の両方で使用する
     */
    val algorithm: Algorithm by lazy {
        val keys = KeyUtils.getKeys()
        Algorithm.RSA256(
            keys.second as RSAPublicKey,
            keys.first as RSAPrivateKey
        )
    }

    /**
     * Bearerトークン認証用のVerifier（アクセストークン・サービストークン）
     *
     * 以前はissuerのURLからJWKSをHTTPで取得していたが、鍵はローカルにあるため直接検証する
     * ロードバランサ越しに自分自身へリクエストする必要がなくなり、起動直後や障害時も検証できる
     */
    val bearerVerifier: JWTVerifier
        get() = get<TokenVerifiers>().bearer

    /**
     * refresh_tokenグラント用のVerifier（リフレッシュトークン）
     */
    val refreshTokenVerifier: JWTVerifier
        get() = get<TokenVerifiers>().refreshToken

    /**
     * トークンイントロスペクション用のVerifier（アクセス・リフレッシュ・サービストークン）
     */
    val introspectionVerifier: JWTVerifier
        get() = get<TokenVerifiers>().introspection

    /**
     * トークン失効用のVerifier（アクセス・リフレッシュトークン、期限切れにも猶予あり）
     */
    val revocationVerifier: JWTVerifier
        get() = get<TokenVerifiers>().revocation

    /**
     * id_token_hint用のVerifier（ID Token、期限切れにも猶予あり）
     */
    val idTokenHintVerifier: JWTVerifier
        get() = get<TokenVerifiers>().idTokenHint

    /**
     * 種別を示すtypヘッダーとtoken_typeクレームを設定済みのJWTビルダーを返す
     * 発行側の種別指定をここに集約し、Verifier側の判定規則と食い違わないようにする
     *
     * @param kind 発行するトークンの種別
     * @return JWTビルダー
     * @throws IllegalArgumentException 発行できない種別（UNKNOWN）を指定した場合
     */
    fun newToken(kind: TokenKind): JWTCreator.Builder {
        val typ = requireNotNull(kind.typ) { "Token kind $kind cannot be issued" }
        val builder = JWT.create().withHeader(mapOf<String, Any>(TYP_HEADER to typ))
        return kind.tokenTypeClaim?.let { builder.withClaim(TokenKind.TOKEN_TYPE_CLAIM, it) } ?: builder
    }

    // JOSEヘッダーのtypパラメータ名
    private const val TYP_HEADER = "typ"
}
