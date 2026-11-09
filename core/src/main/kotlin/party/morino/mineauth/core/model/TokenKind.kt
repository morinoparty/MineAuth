package party.morino.mineauth.core.model

import com.auth0.jwt.interfaces.DecodedJWT

// java-jwtの既定値であり、ID Tokenと移行期間中の旧トークンが持つtyp
private const val GENERIC_TYP = "JWT"

/**
 * MineAuthが発行するJWTの種別
 *
 * 全種別が同じ署名鍵とissuerを共有するため、種別の取り違えを防ぐ手段はJOSEヘッダーのtypと
 * token_typeクレームだけである。検証規則が種別間で排他になるようにこの列挙で一元管理する
 * （RFC 8725 Section 3.11 / 3.12）
 *
 * @property typ 発行時にJOSEヘッダーへ設定するtyp（UNKNOWNは発行しないためnull）
 * @property tokenTypeClaim 発行時に設定するtoken_typeクレーム（ID Tokenは持たないためnull）
 */
enum class TokenKind(val typ: String?, val tokenTypeClaim: String?) {
    // RFC 9068 Section 2.1 のJWTアクセストークン
    ACCESS("at+jwt", "token"),

    // リフレッシュトークン（MineAuth独自のtyp）
    REFRESH("rt+jwt", "refresh_token"),

    // サービスアカウントトークン（MineAuth独自のtyp）
    SERVICE("service+jwt", "service_token"),

    // OIDCのID Token。RPとの互換性を優先してtypはJWTのままとする（RFC 8725 Section 3.11）
    ID(GENERIC_TYP, null),

    // 上記のいずれにも当てはまらないトークン。どの消費者も受け付けない
    UNKNOWN(null, null);

    companion object {
        // 種別を表す非公開クレームの名前
        const val TOKEN_TYPE_CLAIM = "token_type"

        /**
         * JWTの種別を判定する純粋関数
         *
         * 明示的なtypを持つトークンはtypとtoken_typeクレームの両方が一致する場合のみその種別とする。
         * typがJWTのトークンはID Tokenか移行前に発行された旧トークンであり、token_typeクレームで判定する。
         *
         * 旧トークンの判定を外せるのは、最長の有効期間を持つサービストークン（1年）が失効した後である。
         *
         * @param typ JOSEヘッダーのtyp
         * @param tokenType token_typeクレームの値
         * @param subject subクレームの値
         * @return 判定された種別。判定できない場合はUNKNOWN
         */
        fun classify(typ: String?, tokenType: String?, subject: String?): TokenKind = when (typ) {
            null -> UNKNOWN
            GENERIC_TYP -> classifyGenericTyp(tokenType, subject)
            else -> entries.firstOrNull { it.typ == typ && it.tokenTypeClaim == tokenType } ?: UNKNOWN
        }

        /**
         * typがJWTのトークンを判定する
         * token_typeクレームを持たず、subを持つものだけをID Tokenとして扱う
         *
         * @param tokenType token_typeクレームの値
         * @param subject subクレームの値
         * @return 判定された種別
         */
        private fun classifyGenericTyp(tokenType: String?, subject: String?): TokenKind = when {
            tokenType != null -> entries.firstOrNull { it.tokenTypeClaim == tokenType } ?: UNKNOWN
            subject != null -> ID
            else -> UNKNOWN
        }
    }
}

/**
 * デコード済みJWTの種別を判定する
 *
 * @return 判定された種別
 */
fun DecodedJWT.tokenKind(): TokenKind =
    TokenKind.classify(type, getClaim(TokenKind.TOKEN_TYPE_CLAIM).asString(), subject)
