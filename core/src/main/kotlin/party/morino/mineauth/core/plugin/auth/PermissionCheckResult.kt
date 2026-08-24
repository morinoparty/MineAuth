package party.morino.mineauth.core.plugin.auth

/**
 * パーミッション評価の結果
 *
 * 「拒否」と「評価不能」を区別することで、認可バイパスを起こさずに
 * オフラインプレイヤーの扱いを呼び出し側で決められるようにする
 */
enum class PermissionCheckResult {
    /** パーミッションを持っている */
    GRANTED,

    /** パーミッションを持っていない */
    DENIED,

    /**
     * パーミッションを評価できなかった
     * プレイヤーがオフラインで、かつオフライン評価を提供するパーミッションプラグインが無い場合
     */
    UNRESOLVABLE
}
