package party.morino.mineauth.core.web.router.auth.data

import kotlinx.serialization.Serializable

/**
 * 認可画面（React）に渡すビューモデル
 * core/src/main/frontend/src/model.ts の AuthorizeModel と同じ形を保つこと
 */
@Serializable
data class AuthorizePageModel(
    val clientId: String,
    val clientName: String,
    val redirectUri: String,
    val responseType: String,
    val state: String,
    // 正規化済みのスコープ文字列（hiddenフィールドでそのまま送り返す）
    val scope: String,
    // 表示用に分割したスコープ
    val scopeList: List<String>,
    val issuer: String,
    val codeChallenge: String,
    val codeChallengeMethod: String,
    val logoUrl: String,
    val applicationName: String,
    // OIDC nonce: リクエストに含まれていた場合のみ
    val nonce: String? = null,
)
