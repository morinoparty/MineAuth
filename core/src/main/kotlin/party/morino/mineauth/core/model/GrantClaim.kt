package party.morino.mineauth.core.model

import com.auth0.jwt.interfaces.Payload

// トークンが属するグラントを表す非公開クレームの名前
// アクセストークンとリフレッシュトークンだけが持ち、ID Tokenとサービストークンには付けない
const val GRANT_ID_CLAIM = "grant_id"

/**
 * トークンが属するグラントのIDを返す
 *
 * グラントは1回の認可（認可コードの発行）とそこから続くローテーションの連なりであり、
 * 不正利用を検知したときにその連なり全体をまとめて失効させる単位になる
 *
 * @return グラントID。grant_idの導入前に発行された旧形式のトークンはnull
 */
fun Payload.grantId(): String? = getClaim(GRANT_ID_CLAIM).asString()
