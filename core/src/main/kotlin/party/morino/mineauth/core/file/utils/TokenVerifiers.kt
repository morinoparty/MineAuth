package party.morino.mineauth.core.file.utils

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import com.auth0.jwt.interfaces.Verification
import party.morino.mineauth.core.model.TokenKind

/**
 * 用途ごとのJWTVerifierを束ねるクラス
 *
 * 全てのVerifierが署名とissuerを検証したうえで、受け付けるトークン種別と有効期限の猶予だけが異なる。
 * 用途の定義を1か所に集めることで、種別間の排他性（RFC 8725 Section 3.12）を確認しやすくする
 *
 * @param algorithm 署名検証に使うアルゴリズム
 * @param acceptedIssuers 受け付けるissuerの一覧
 */
class TokenVerifiers(
    private val algorithm: Algorithm,
    acceptedIssuers: List<String>
) {
    // JWT.require().withIssuer()がvarargを取るため配列に変換する
    private val issuers: Array<String> = acceptedIssuers.toTypedArray()

    /**
     * Bearerトークン認証用（アクセストークンとサービストークン）
     *
     * Ktorの2つのプロバイダで共有するため両方を通し、どちらの種別かは各プロバイダのvalidateで絞り込む。
     * 複数ノード間の時刻ずれのみ許容する
     */
    val bearer: JWTVerifier = restrictedTo(setOf(TokenKind.ACCESS, TokenKind.SERVICE)) {
        acceptLeeway(CLOCK_SKEW_LEEWAY_SECONDS)
    }

    /**
     * トークンエンドポイントのrefresh_tokenグラント用（リフレッシュトークンのみ）
     */
    val refreshToken: JWTVerifier = restrictedTo(setOf(TokenKind.REFRESH))

    /**
     * トークンイントロスペクション用（RFC 7662で状態を報告する種別のみ）
     *
     * Bearer認証で受け付けるトークンをinactiveと報告しないよう、Bearer認証と同じ時刻ずれを許容する
     */
    val introspection: JWTVerifier = restrictedTo(setOf(TokenKind.ACCESS, TokenKind.REFRESH, TokenKind.SERVICE)) {
        acceptLeeway(CLOCK_SKEW_LEEWAY_SECONDS)
    }

    /**
     * トークン失効用（RFC 7009の対象であるアクセストークンとリフレッシュトークンのみ）
     *
     * 期限切れのトークンも失効の登録対象とするため、有効期限に猶予を設ける
     */
    val revocation: JWTVerifier = restrictedTo(setOf(TokenKind.ACCESS, TokenKind.REFRESH)) {
        acceptExpiresAt(REVOCATION_EXPIRY_LEEWAY_SECONDS)
    }

    /**
     * RP-Initiated Logoutのid_token_hint用（ID Tokenのみ）
     *
     * 仕様は期限切れのID Tokenも受け付けることを求めているが、対象は現在または直近のセッションを持つRPである。
     * そのため無期限にはせず、有効期限に上限付きの猶予を設ける
     */
    val idTokenHint: JWTVerifier = restrictedTo(setOf(TokenKind.ID)) {
        acceptExpiresAt(ID_TOKEN_HINT_EXPIRY_LEEWAY_SECONDS)
    }

    /**
     * 署名とissuerの検証に種別の制限を加えたVerifierを構築する
     *
     * @param kinds 受け付けるトークン種別
     * @param configure 有効期限の猶予など、用途ごとの追加設定
     * @return 構築されたVerifier
     */
    private fun restrictedTo(kinds: Set<TokenKind>, configure: Verification.() -> Unit = {}): JWTVerifier {
        val delegate = JWT.require(algorithm)
            .withIssuer(*issuers)
            .apply(configure)
            .build()
        return KindRestrictedVerifier(delegate, kinds)
    }

    private companion object {
        // Bearer認証とイントロスペクションで共通して許容する時刻ずれ（秒）
        const val CLOCK_SKEW_LEEWAY_SECONDS = 3L

        // リフレッシュトークンの有効期間（30日）に合わせた失効時の猶予（秒）
        // java-jwtは現在時刻からこの値を引くため、Long.MAX_VALUEのような値はInstantの範囲を超えて例外になる
        const val REVOCATION_EXPIRY_LEEWAY_SECONDS = 30L * 24 * 60 * 60

        // id_token_hintを「直近のセッション」とみなす期間（秒）
        const val ID_TOKEN_HINT_EXPIRY_LEEWAY_SECONDS = 30L * 24 * 60 * 60
    }
}
