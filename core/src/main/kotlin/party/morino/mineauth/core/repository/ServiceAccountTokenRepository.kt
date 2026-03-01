package party.morino.mineauth.core.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import party.morino.mineauth.core.database.ServiceAccountTokens
import java.time.LocalDateTime

/**
 * サービスアカウントトークンデータ
 */
data class ServiceAccountTokenData(
    val tokenId: String,
    val accountId: String,
    val tokenHash: String,
    val createdBy: String,
    val createdAt: LocalDateTime,
    val lastUsedAt: LocalDateTime?,
    val revoked: Boolean
)

/**
 * サービスアカウントトークン操作エラー
 */
sealed class ServiceAccountTokenError {
    data object NotFound : ServiceAccountTokenError()
    data object AlreadyRevoked : ServiceAccountTokenError()
    data class DatabaseError(val message: String) : ServiceAccountTokenError()
}

/**
 * サービスアカウントトークンリポジトリ
 * ServiceAccountTokensテーブルに対するCRUD操作を提供する
 */
object ServiceAccountTokenRepository {

    /**
     * 新しいトークンメタデータを保存する
     *
     * @param tokenId JWT ID（jti）
     * @param accountId 対象のアカウントID
     * @param tokenHash トークンのSHA-256ハッシュ
     * @param createdBy 作成者のプレイヤーUUID
     * @return 成功時はトークンデータ、失敗時はエラー
     */
    suspend fun create(
        tokenId: String,
        accountId: String,
        tokenHash: String,
        createdBy: String
    ): Either<ServiceAccountTokenError, ServiceAccountTokenData> = newSuspendedTransaction {
        try {
            ServiceAccountTokens.insert {
                it[ServiceAccountTokens.tokenId] = tokenId
                it[ServiceAccountTokens.accountId] = accountId
                it[ServiceAccountTokens.tokenHash] = tokenHash
                it[ServiceAccountTokens.createdBy] = createdBy
            }

            ServiceAccountTokenData(
                tokenId = tokenId,
                accountId = accountId,
                tokenHash = tokenHash,
                createdBy = createdBy,
                createdAt = LocalDateTime.now(),
                lastUsedAt = null,
                revoked = false
            ).right()
        } catch (e: Exception) {
            ServiceAccountTokenError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * トークンIDでトークンを検索する
     */
    suspend fun findByTokenId(tokenId: String): Either<ServiceAccountTokenError, ServiceAccountTokenData> =
        newSuspendedTransaction {
            try {
                val row = ServiceAccountTokens.selectAll()
                    .where { ServiceAccountTokens.tokenId eq tokenId }
                    .firstOrNull()

                if (row == null) {
                    ServiceAccountTokenError.NotFound.left()
                } else {
                    row.toTokenData().right()
                }
            } catch (e: Exception) {
                ServiceAccountTokenError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }

    /**
     * アカウントIDに紐づく全トークンを取得する
     */
    suspend fun findByAccountId(accountId: String): Either<ServiceAccountTokenError, List<ServiceAccountTokenData>> =
        newSuspendedTransaction {
            try {
                val rows = ServiceAccountTokens.selectAll()
                    .where { ServiceAccountTokens.accountId eq accountId }
                    .map { it.toTokenData() }
                rows.right()
            } catch (e: Exception) {
                ServiceAccountTokenError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }

    /**
     * アカウントIDに紐づく全トークンを失効させる
     */
    suspend fun revokeByAccountId(accountId: String): Either<ServiceAccountTokenError, Int> =
        newSuspendedTransaction {
            try {
                val count = ServiceAccountTokens.update(
                    where = { ServiceAccountTokens.accountId eq accountId }
                ) {
                    it[revoked] = true
                }
                count.right()
            } catch (e: Exception) {
                ServiceAccountTokenError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }

    /**
     * 特定トークンを失効させる
     */
    suspend fun revokeByTokenId(tokenId: String): Either<ServiceAccountTokenError, Unit> =
        newSuspendedTransaction {
            try {
                val count = ServiceAccountTokens.update(
                    where = { ServiceAccountTokens.tokenId eq tokenId }
                ) {
                    it[revoked] = true
                }

                if (count == 0) {
                    ServiceAccountTokenError.NotFound.left()
                } else {
                    Unit.right()
                }
            } catch (e: Exception) {
                ServiceAccountTokenError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }

    /**
     * トークンが有効かどうかを確認する（revoked = false）
     */
    suspend fun isTokenValid(tokenId: String): Boolean = newSuspendedTransaction {
        try {
            val row = ServiceAccountTokens.selectAll()
                .where { ServiceAccountTokens.tokenId eq tokenId }
                .firstOrNull()

            row != null && !row[ServiceAccountTokens.revoked]
        } catch (e: Exception) {
            false
        }
    }

    /**
     * ブロッキング版のトークン有効性チェック（JWT validation callback用）
     */
    fun isTokenValidBlocking(tokenId: String): Boolean = runBlocking {
        isTokenValid(tokenId)
    }

    /**
     * 最終使用日時を更新する
     */
    suspend fun updateLastUsedAt(tokenId: String): Either<ServiceAccountTokenError, Unit> =
        newSuspendedTransaction {
            try {
                ServiceAccountTokens.update(
                    where = { ServiceAccountTokens.tokenId eq tokenId }
                ) {
                    it[lastUsedAt] = LocalDateTime.now()
                }
                Unit.right()
            } catch (e: Exception) {
                ServiceAccountTokenError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }

    /**
     * ResultRowからServiceAccountTokenDataに変換する
     */
    private fun ResultRow.toTokenData(): ServiceAccountTokenData = ServiceAccountTokenData(
        tokenId = this[ServiceAccountTokens.tokenId],
        accountId = this[ServiceAccountTokens.accountId],
        tokenHash = this[ServiceAccountTokens.tokenHash],
        createdBy = this[ServiceAccountTokens.createdBy],
        createdAt = this[ServiceAccountTokens.createdAt],
        lastUsedAt = this[ServiceAccountTokens.lastUsedAt],
        revoked = this[ServiceAccountTokens.revoked]
    )
}
