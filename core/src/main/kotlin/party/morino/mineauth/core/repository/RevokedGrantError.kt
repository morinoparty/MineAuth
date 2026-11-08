package party.morino.mineauth.core.repository

/**
 * 失効グラント操作エラー
 */
sealed class RevokedGrantError {
    data class DatabaseError(val message: String) : RevokedGrantError()
}
