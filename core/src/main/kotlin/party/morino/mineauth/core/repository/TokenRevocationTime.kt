package party.morino.mineauth.core.repository

import java.time.Instant

/**
 * トークンの失効時刻の読み取り結果
 *
 * 失効記録の有無に加えて、記録に失効した時刻が残っているかを区別する。
 * リフレッシュトークンの再提示がgrace期間内かは時刻が分かる場合にしか判定できないため、
 * 時刻のない記録を特別な値（0やnull）で表さず、別の状態として扱う
 */
sealed interface TokenRevocationTime {
    // 失効記録がない（未使用のトークン）
    data object NotRevoked : TokenRevocationTime

    /**
     * 失効記録があり、失効した時刻が記録されている
     *
     * @property revokedAt 失効した時刻
     */
    data class Recorded(val revokedAt: Instant) : TokenRevocationTime

    // 失効記録はあるが時刻が記録されていない
    // revoked_at_ms の追加前に書かれた行や、追加前のバージョンが書いた行がこれにあたる
    data object Unrecorded : TokenRevocationTime
}
