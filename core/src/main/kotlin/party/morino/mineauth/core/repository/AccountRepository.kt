package party.morino.mineauth.core.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.uuid.Generators
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import party.morino.mineauth.core.database.Accounts
import java.util.*

/**
 * アカウントの種別
 */
enum class AccountType(val value: String) {
    PLAYER("player"),
    SERVICE("service");

    companion object {
        fun fromValue(value: String): AccountType? = entries.find { it.value == value }
    }
}

/**
 * アカウントデータ
 */
data class AccountData(
    val accountId: String,
    val accountType: AccountType,
    val identifier: String
)

/**
 * アカウント操作エラー
 */
sealed class AccountError {
    data object NotFound : AccountError()
    data object AlreadyExists : AccountError()
    data class DatabaseError(val message: String) : AccountError()
}

/**
 * アカウントリポジトリ
 * Accountsテーブルに対するCRUD操作を提供する
 */
object AccountRepository {
    // UUIDv7生成器（時間ソート可能なUUID）
    private val uuidGenerator = Generators.timeBasedEpochGenerator()

    /**
     * 新しいアカウントを作成する
     *
     * @param accountType アカウント種別（player または service）
     * @param identifier プレイヤーUUID またはサービス名
     * @return 成功時は作成されたアカウントデータ、失敗時はエラー
     */
    suspend fun create(
        accountType: AccountType,
        identifier: String
    ): Either<AccountError, AccountData> = newSuspendedTransaction {
        try {
            // 既に同じidentifierで同じtypeのアカウントが存在するかチェック
            val existing = Accounts.selectAll()
                .where { (Accounts.accountType eq accountType.value) and (Accounts.identifier eq identifier) }
                .firstOrNull()

            if (existing != null) {
                return@newSuspendedTransaction AccountError.AlreadyExists.left()
            }

            // UUIDv7を生成
            val accountId = uuidGenerator.generate().toString()

            Accounts.insert {
                it[Accounts.accountId] = accountId
                it[Accounts.accountType] = accountType.value
                it[Accounts.identifier] = identifier
            }

            AccountData(
                accountId = accountId,
                accountType = accountType,
                identifier = identifier
            ).right()
        } catch (e: Exception) {
            AccountError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * アカウントIDでアカウントを取得する
     *
     * @param accountId アカウントID（UUIDv7）
     * @return 成功時はアカウントデータ、失敗時はエラー
     */
    suspend fun findById(accountId: String): Either<AccountError, AccountData> = newSuspendedTransaction {
        try {
            val row = Accounts.selectAll()
                .where { Accounts.accountId eq accountId }
                .firstOrNull()

            if (row == null) {
                AccountError.NotFound.left()
            } else {
                row.toAccountData().right()
            }
        } catch (e: Exception) {
            AccountError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * identifierでアカウントを取得する
     *
     * @param accountType アカウント種別
     * @param identifier プレイヤーUUID またはサービス名
     * @return 成功時はアカウントデータ、失敗時はエラー
     */
    suspend fun findByIdentifier(
        accountType: AccountType,
        identifier: String
    ): Either<AccountError, AccountData> = newSuspendedTransaction {
        try {
            val row = Accounts.selectAll()
                .where { (Accounts.accountType eq accountType.value) and (Accounts.identifier eq identifier) }
                .firstOrNull()

            if (row == null) {
                AccountError.NotFound.left()
            } else {
                row.toAccountData().right()
            }
        } catch (e: Exception) {
            AccountError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * プレイヤーUUIDでアカウントを取得または作成する
     *
     * @param playerUuid プレイヤーのMinecraft UUID
     * @return アカウントデータ
     */
    suspend fun getOrCreatePlayerAccount(playerUuid: UUID): Either<AccountError, AccountData> {
        val existing = findByIdentifier(AccountType.PLAYER, playerUuid.toString())
        return existing.fold(
            ifLeft = { error ->
                if (error is AccountError.NotFound) {
                    create(AccountType.PLAYER, playerUuid.toString())
                } else {
                    error.left()
                }
            },
            ifRight = { it.right() }
        )
    }

    /**
     * ResultRowからAccountDataに変換する
     */
    private fun ResultRow.toAccountData(): AccountData = AccountData(
        accountId = this[Accounts.accountId],
        accountType = AccountType.fromValue(this[Accounts.accountType]) ?: AccountType.PLAYER,
        identifier = this[Accounts.identifier]
    )
}
