package party.morino.mineauth.core.file.utils

import com.auth0.jwt.exceptions.InvalidClaimException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.JWTVerifier
import party.morino.mineauth.core.model.TokenKind
import party.morino.mineauth.core.model.tokenKind

/**
 * 許可された種別のJWTだけを通すJWTVerifierのデコレータ
 *
 * java-jwtのVerifierはペイロードしか検証できず、KtorのvalidateブロックにもJOSEヘッダーは渡されない。
 * そのためtypの検証は署名検証と同じ段階でここに集約し、消費者側の確認漏れを構造的に防ぐ（RFC 8725 Section 3.12）
 *
 * @param delegate 署名・issuer・有効期限を検証するVerifier
 * @param acceptedKinds 受け付けるトークン種別
 */
class KindRestrictedVerifier(
    private val delegate: JWTVerifier,
    private val acceptedKinds: Set<TokenKind>
) : JWTVerifier {

    /**
     * 文字列のトークンを検証する
     *
     * @param token 検証するJWT
     * @return 検証済みのJWT
     * @throws com.auth0.jwt.exceptions.JWTVerificationException 検証に失敗した場合
     */
    override fun verify(token: String): DecodedJWT = ensureAcceptedKind(delegate.verify(token))

    /**
     * デコード済みのトークンを検証する
     *
     * @param jwt 検証するJWT
     * @return 検証済みのJWT
     * @throws com.auth0.jwt.exceptions.JWTVerificationException 検証に失敗した場合
     */
    override fun verify(jwt: DecodedJWT): DecodedJWT = ensureAcceptedKind(delegate.verify(jwt))

    /**
     * 署名検証後のJWTの種別を確認する
     * 未検証のヘッダーやクレームを判定に使わないよう、必ず署名検証の後に呼び出す
     *
     * @param jwt 署名検証済みのJWT
     * @return 受け付ける種別であれば引数のJWT
     * @throws InvalidClaimException 受け付けない種別の場合
     */
    private fun ensureAcceptedKind(jwt: DecodedJWT): DecodedJWT {
        if (jwt.tokenKind() !in acceptedKinds) {
            throw InvalidClaimException("The token kind is not accepted by this verifier")
        }
        return jwt
    }
}
