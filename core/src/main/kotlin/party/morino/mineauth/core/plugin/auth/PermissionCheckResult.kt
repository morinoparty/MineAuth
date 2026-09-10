package party.morino.mineauth.core.plugin.auth

/**
 * パーミッション評価の結果
 *
 * LuckPermsをハード依存とすることで、オフラインプレイヤーも常に評価可能となった。
 * 評価結果は「許可」か「拒否」のどちらかであり、評価不能という状態は存在しない。
 */
enum class PermissionCheckResult {
    /** パーミッションを持っている */
    GRANTED,

    /** パーミッションを持っていない */
    DENIED
}
