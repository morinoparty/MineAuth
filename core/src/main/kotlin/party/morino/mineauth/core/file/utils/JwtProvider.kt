package party.morino.mineauth.core.file.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import party.morino.mineauth.core.file.data.JWTConfigData
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey

/**
 * JWT署名・検証に使用するAlgorithmとVerifierを提供するユーティリティ
 *
 * KeyUtilsのキャッシュ済み鍵ペアとJWTConfigDataの発行者情報を組み合わせて構築する
 * 各プロパティはlazyで初期化され、スレッドセーフにキャッシュされる
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
     * 標準JWTVerifier（署名 + issuer + 有効期限を検証）
     * トークンイントロスペクション、リフレッシュトークン検証等で使用
     * issuerは自サーバーとtrustedIssuersのいずれかに一致すればよい（署名鍵は共有前提）
     */
    val verifier: JWTVerifier by lazy {
        JWT.require(algorithm)
            .withIssuer(*acceptedIssuers())
            .build()
    }

    /**
     * Bearerトークン認証用のJWTVerifier（標準Verifierに時刻ずれの許容を加えたもの）
     *
     * 以前はissuerのURLからJWKSをHTTPで取得していたが、鍵はローカルにあるため直接検証する
     * ロードバランサ越しに自分自身へリクエストする必要がなくなり、起動直後や障害時も検証できる
     */
    val bearerVerifier: JWTVerifier by lazy {
        JWT.require(algorithm)
            .withIssuer(*acceptedIssuers())
            .acceptLeeway(BEARER_LEEWAY_SECONDS)
            .build()
    }

    /**
     * 有効期限を無視するJWTVerifier（署名 + issuerのみ検証）
     *
     * EndSessionのid_token_hint検証やRevokeの期限切れトークン処理で使用
     * ログアウト時やトークン失効時はトークンが期限切れの可能性があるため
     * acceptExpiresAtで期限切れを安全に許容しつつ、署名検証は常に実行する
     */
    val lenientVerifier: JWTVerifier by lazy {
        JWT.require(algorithm)
            .withIssuer(*acceptedIssuers())
            .acceptExpiresAt(Long.MAX_VALUE)
            .build()
    }

    /**
     * 検証時に受け付けるissuerの配列を返す
     * JWT.require().withIssuer()がvarargを取るため配列に変換する
     */
    private fun acceptedIssuers(): Array<String> = get<JWTConfigData>().acceptedIssuers.toTypedArray()

    // Bearerトークン検証で許容する時刻ずれ（秒）
    private const val BEARER_LEEWAY_SECONDS = 3L
}
