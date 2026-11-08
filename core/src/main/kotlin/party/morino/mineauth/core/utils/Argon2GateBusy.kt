package party.morino.mineauth.core.utils

/**
 * Argon2Gateの許可を待機時間内に得られず、ハッシュを計算しなかったことを表す
 *
 * 認証情報を評価していないので、認証の失敗としては扱わず、流量制限のバックオフにも数えない。
 * 混雑は短時間で解消する想定なので、すぐに再試行してよいことを案内する
 */
data object Argon2GateBusy {
    // Retry-Afterで案内する再試行までの秒数
    const val RETRY_AFTER_SECONDS: Long = 1
}
