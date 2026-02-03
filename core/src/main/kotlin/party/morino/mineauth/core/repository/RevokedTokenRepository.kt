package party.morino.mineauth.core.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import party.morino.mineauth.core.database.RevokedTokens
import java.time.LocalDateTime

/**
 * トークン種別
 */
enum class TokenType(val value: String) {
    ACCESS_TOKEN("access_token"),
    REFRESH_TOKEN("refresh_token");

    companion object {
        fun fromValue(value: String): TokenType? = entries.find { it.value == value }
        fun fromHint(hint: String?): TokenType? = when (hint) {
            "access_token" -> ACCESS_TOKEN
            "refresh_token" -> REFRESH_TOKEN
            else -> null
        }
    }
}

/**
 * 失効トークン操作エラー
 */
sealed class RevokedTokenError {
    data object AlreadyRevoked : RevokedTokenError()
    data class DatabaseError(val message: String) : RevokedTokenError()
}

/**
 * 失効トークンリポジトリ
 * RFC 7009 Token Revocationに基づくトークン失効管理
 *
 * JWTトークンはステートレスなため、失効したトークンをブラックリストとして管理する
 */
object RevokedTokenRepository {

    /**
     * トークンを失効させる
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @param tokenType トークン種別
     * @param clientId クライアントID
     * @param expiresAt トークンの有効期限
     * @return 成功時はUnit、失敗時はエラー
     */
    suspend fun revoke(
        tokenId: String,
        tokenType: TokenType,
        clientId: String,
        expiresAt: LocalDateTime
    ): Either<RevokedTokenError, Unit> = newSuspendedTransaction {
        try {
            // 既に失効済みかチェック
            val existing = RevokedTokens.selectAll()
                .where { RevokedTokens.tokenId eq tokenId }
                .firstOrNull()

            if (existing != null) {
                // RFC 7009: 既に失効済みでも成功として扱う
                return@newSuspendedTransaction Unit.right()
            }

            RevokedTokens.insert {
                it[RevokedTokens.tokenId] = tokenId
                it[RevokedTokens.tokenType] = tokenType.value
                it[RevokedTokens.clientId] = clientId
                it[RevokedTokens.expiresAt] = expiresAt
            }

            Unit.right()
        } catch (e: Exception) {
            RevokedTokenError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * トークンが失効済みかチェックする
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @return 失効済みの場合true
     */
    suspend fun isRevoked(tokenId: String): Boolean = newSuspendedTransaction {
        try {
            RevokedTokens.selectAll()
                .where { RevokedTokens.tokenId eq tokenId }
                .firstOrNull() != null
        } catch (e: Exception) {
            // エラー時は安全側に倒して失効済みとして扱う
            true
        }
    }

    /**
     * 期限切れの失効トークンレコードをクリーンアップする
     * 定期的に実行することでテーブルサイズを抑制する
     *
     * @return 削除されたレコード数
     */
    suspend fun cleanupExpired(): Int = newSuspendedTransaction {
        try {
            RevokedTokens.deleteWhere {
                expiresAt less LocalDateTime.now()
            }
        } catch (e: Exception) {
            0
        }
    }

    /**
     * トークンが失効済みかチェックする（ブロッキング版）
     * JWT検証コールバック用のヘルパーメソッド
     *
     * @param tokenId トークンのJWT ID（jti claim）
     * @return 失効済みの場合true
     */
    fun isRevokedBlocking(tokenId: String): Boolean = runBlocking {
        isRevoked(tokenId)
    }
}
