package party.morino.mineauth.core.web.router.auth.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.*

/**
 * 認可コードに紐づくデータ
 * ユーザーが認可した際に生成され、トークン交換時に使用される
 */
@Serializable
data class AuthorizedData(
    val clientId: String,
    val redirectUri: String,
    val scope: String,
    val state: String,
    val codeChallenge: String,
    val codeChallengeMethod: String = "S256",
    val uniqueId: @Serializable(with = UUIDSerializer::class) UUID,
    // OIDC nonce: リプレイ攻撃防止用（Authentication Requestに含まれていた場合のみ）
    val nonce: String? = null,
    // 認証時刻（Unix timestamp ミリ秒）: ID Tokenのauth_timeクレームに使用
    val authTime: Long = System.currentTimeMillis()
)
