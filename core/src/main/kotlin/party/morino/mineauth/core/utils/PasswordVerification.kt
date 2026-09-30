package party.morino.mineauth.core.utils

/**
 * パスワード検証の結果
 */
enum class PasswordVerification {
    // 現在の設定で生成されたハッシュと一致した
    MATCHED,

    // 一致したが、旧pepperまたは旧パラメータのハッシュだった（現在の設定で再ハッシュが必要）
    MATCHED_NEEDS_REHASH,

    // 一致しなかった
    NOT_MATCHED,
}
