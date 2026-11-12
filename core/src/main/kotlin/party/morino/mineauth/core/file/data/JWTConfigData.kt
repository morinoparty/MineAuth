package party.morino.mineauth.core.file.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.*

/**
 * JWTの発行・検証に使う設定
 *
 * @property issuer このサーバーが発行するトークンのiss（OIDC Discoveryのissuerと同じ値）
 * @property trustedIssuers 自サーバー以外に受け付けるiss（署名鍵を共有する他サーバーのissuer）
 */
@Serializable
data class JWTConfigData(
    val issuer: String = "https://api.morino.party",
    val realm: String = "morino.party",
    val privateKeyFile: String = "privateKey.pem",
    val keyId: @Serializable(with = UUIDSerializer::class) UUID,
    val trustedIssuers: List<String> = emptyList()
) {
    /**
     * トークン検証時に受け付けるissの一覧
     * 自サーバーのissuerを先頭に置き、末尾スラッシュの揺れを吸収して重複を除く
     */
    val acceptedIssuers: List<String>
        get() = (listOf(issuer) + trustedIssuers).map(::normalizeIssuer).distinct()

    companion object {
        /**
         * issuer文字列を正規化する
         * OIDCではissuerは文字列として完全一致で比較されるため、末尾スラッシュを除去して表記を揃える
         *
         * @param issuer 正規化前のissuer
         * @return 末尾スラッシュを除去したissuer
         */
        fun normalizeIssuer(issuer: String): String = issuer.trimEnd('/')
    }
}
