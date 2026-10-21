package party.morino.mineauth.core.repository

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.fasterxml.uuid.Generators
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.v1.jdbc.update
import party.morino.mineauth.core.database.OAuthClientRedirectUris
import party.morino.mineauth.core.database.OAuthClients
import party.morino.mineauth.core.utils.Argon2Utils
import party.morino.mineauth.core.web.telemetry.withDatabaseSpan
import java.security.SecureRandom
import java.time.LocalDateTime
import java.util.*

/**
 * OAuthクライアントの種別
 */
enum class ClientType(val value: String) {
    PUBLIC("public"),
    CONFIDENTIAL("confidential");

    companion object {
        fun fromValue(value: String): ClientType? = entries.find { it.value == value }
    }
}

/**
 * OAuthクライアントデータ（レスポンス用、シークレットハッシュは含まない）
 */
data class OAuthClientData(
    val clientId: String,
    val clientName: String,
    val clientType: ClientType,
    // 登録順のリダイレクトURI（1件以上）
    val redirectUris: List<String>,
    val issuerAccountId: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

/**
 * OAuthクライアント作成結果（シークレット付き、作成時のみ平文シークレットを返す）
 */
data class OAuthClientCreationResult(
    val client: OAuthClientData,
    val clientSecret: String? // Publicクライアントの場合はnull
)

/**
 * OAuthクライアント操作エラー
 */
sealed class OAuthClientError {
    data object NotFound : OAuthClientError()
    data object InvalidCredentials : OAuthClientError()
    data object IssuerAccountNotFound : OAuthClientError()
    data class DatabaseError(val message: String) : OAuthClientError()
}

/**
 * OAuthクライアントリポジトリ
 * OAuth2/OIDCクライアントのCRUD操作を提供する
 */
object OAuthClientRepository {
    // UUIDv7生成器（時間ソート可能なUUID）
    private val uuidGenerator = Generators.timeBasedEpochGenerator()

    // シークレット生成用のセキュアランダム
    private val secureRandom = SecureRandom()

    // シークレットの長さ（バイト）
    private const val SECRET_LENGTH = 32

    /**
     * ランダムなクライアントシークレットを生成する
     */
    private fun generateClientSecret(): String {
        val bytes = ByteArray(SECRET_LENGTH)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * 新しいOAuthクライアントを作成する
     *
     * @param clientName クライアント名
     * @param clientType クライアント種別（public または confidential）
     * @param redirectUris リダイレクトURI（登録順に保存する）
     * @param issuerAccountId 発行者のアカウントID
     * @return 成功時は作成されたクライアント（Confidentialの場合は平文シークレット付き）、失敗時はエラー
     */
    suspend fun create(
        clientName: String,
        clientType: ClientType,
        redirectUris: List<String>,
        issuerAccountId: String
    ): Either<OAuthClientError, OAuthClientCreationResult> = newSuspendedTransaction {
        try {
            // 発行者アカウントの存在確認
            val issuerExists = AccountRepository.findById(issuerAccountId)
            if (issuerExists.isLeft()) {
                return@newSuspendedTransaction OAuthClientError.IssuerAccountNotFound.left()
            }

            // UUIDv7を生成
            val clientId = uuidGenerator.generate().toString()
            val now = LocalDateTime.now()

            // シークレットの生成とハッシュ化（Confidentialクライアントのみ）
            val (plainSecret, secretHash) = if (clientType == ClientType.CONFIDENTIAL) {
                val secret = generateClientSecret()
                val hash = Argon2Utils.hashSecret(secret)
                secret to hash
            } else {
                null to null
            }

            OAuthClients.insert {
                it[OAuthClients.clientId] = clientId
                it[OAuthClients.clientName] = clientName
                it[OAuthClients.clientType] = clientType.value
                it[OAuthClients.clientSecretHash] = secretHash
                it[OAuthClients.issuerAccountId] = issuerAccountId
                it[OAuthClients.createdAt] = now
                it[OAuthClients.updatedAt] = now
            }
            // クライアントと同じトランザクションで登録し、URIのないクライアントが残らないようにする
            insertRedirectUris(clientId, redirectUris)

            OAuthClientCreationResult(
                client = OAuthClientData(
                    clientId = clientId,
                    clientName = clientName,
                    clientType = clientType,
                    redirectUris = redirectUris,
                    issuerAccountId = issuerAccountId,
                    createdAt = now,
                    updatedAt = now
                ),
                clientSecret = plainSecret
            ).right()
        } catch (e: Exception) {
            OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * クライアントIDでクライアントを取得する
     *
     * @param clientId クライアントID
     * @return 成功時はクライアントデータ、失敗時はエラー
     */
    suspend fun findById(clientId: String): Either<OAuthClientError, OAuthClientData> =
        withDatabaseSpan("oauth_clients", "select") {
            newSuspendedTransaction {
                try {
                    val row = OAuthClients.selectAll()
                        .where { OAuthClients.clientId eq clientId }
                        .firstOrNull()

                    if (row == null) {
                        OAuthClientError.NotFound.left()
                    } else {
                        row.toOAuthClientData(findRedirectUris(clientId)).right()
                    }
                } catch (e: Exception) {
                    OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
                }
            }
        }

    /**
     * クライアントシークレットを検証する
     *
     * @param clientId クライアントID
     * @param clientSecret 検証するシークレット
     * @return 成功時はクライアントデータ、失敗時はエラー
     */
    suspend fun verifyClientSecret(
        clientId: String,
        clientSecret: String
    ): Either<OAuthClientError, OAuthClientData> = withDatabaseSpan("oauth_clients", "select") {
        newSuspendedTransaction {
            try {
                val row = OAuthClients.selectAll()
                    .where { OAuthClients.clientId eq clientId }
                    .firstOrNull()

                if (row == null) {
                    return@newSuspendedTransaction OAuthClientError.NotFound.left()
                }

                val clientType = ClientType.fromValue(row[OAuthClients.clientType])
                if (clientType != ClientType.CONFIDENTIAL) {
                    // Publicクライアントにはシークレット検証は不要
                    return@newSuspendedTransaction OAuthClientError.InvalidCredentials.left()
                }

                val storedHash = row[OAuthClients.clientSecretHash]
                if (storedHash == null) {
                    return@newSuspendedTransaction OAuthClientError.InvalidCredentials.left()
                }

                // Argon2idで検証（定数時間比較）
                if (!Argon2Utils.verifySecret(clientSecret, storedHash)) {
                    return@newSuspendedTransaction OAuthClientError.InvalidCredentials.left()
                }

                row.toOAuthClientData(findRedirectUris(clientId)).right()
            } catch (e: Exception) {
                OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }
    }

    /**
     * クライアント情報を更新する
     *
     * @param clientId クライアントID
     * @param clientName 新しいクライアント名（nullの場合は更新しない）
     * @param redirectUris 新しいリダイレクトURIの一覧（nullの場合は更新しない。指定時は全件を置き換える）
     * @return 成功時は更新されたクライアントデータ、失敗時はエラー
     */
    suspend fun update(
        clientId: String,
        clientName: String? = null,
        redirectUris: List<String>? = null
    ): Either<OAuthClientError, OAuthClientData> = newSuspendedTransaction {
        try {
            val existing = OAuthClients.selectAll()
                .where { OAuthClients.clientId eq clientId }
                .firstOrNull()

            if (existing == null) {
                return@newSuspendedTransaction OAuthClientError.NotFound.left()
            }

            OAuthClients.update({ OAuthClients.clientId eq clientId }) {
                clientName?.let { name -> it[OAuthClients.clientName] = name }
                it[updatedAt] = LocalDateTime.now()
            }

            // リダイレクトURIは順序ごと置き換えるため、全件削除してから登録し直す
            redirectUris?.let { uris ->
                OAuthClientRedirectUris.deleteWhere { OAuthClientRedirectUris.clientId eq clientId }
                insertRedirectUris(clientId, uris)
            }

            // 更新後のデータを取得して返す
            val updated = OAuthClients.selectAll()
                .where { OAuthClients.clientId eq clientId }
                .first()

            updated.toOAuthClientData(findRedirectUris(clientId)).right()
        } catch (e: Exception) {
            OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * クライアントシークレットをリセットする（Confidentialクライアントのみ）
     *
     * @param clientId クライアントID
     * @return 成功時は新しいシークレット、失敗時はエラー
     */
    suspend fun resetClientSecret(clientId: String): Either<OAuthClientError, String> = newSuspendedTransaction {
        try {
            val existing = OAuthClients.selectAll()
                .where { OAuthClients.clientId eq clientId }
                .firstOrNull()

            if (existing == null) {
                return@newSuspendedTransaction OAuthClientError.NotFound.left()
            }

            val clientType = ClientType.fromValue(existing[OAuthClients.clientType])
            if (clientType != ClientType.CONFIDENTIAL) {
                return@newSuspendedTransaction OAuthClientError.InvalidCredentials.left()
            }

            // 新しいシークレットを生成
            val newSecret = generateClientSecret()
            val newHash = Argon2Utils.hashSecret(newSecret)

            OAuthClients.update({ OAuthClients.clientId eq clientId }) {
                it[clientSecretHash] = newHash
                it[updatedAt] = LocalDateTime.now()
            }

            newSecret.right()
        } catch (e: Exception) {
            OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * 発行者アカウントIDでクライアント一覧を取得する
     *
     * @param issuerAccountId 発行者のアカウントID
     * @return クライアントデータのリスト
     */
    suspend fun findByIssuerAccountId(issuerAccountId: String): Either<OAuthClientError, List<OAuthClientData>> =
        newSuspendedTransaction {
            try {
                val rows = OAuthClients.selectAll()
                    .where { OAuthClients.issuerAccountId eq issuerAccountId }
                    .toList()

                rows.withRedirectUris().right()
            } catch (e: Exception) {
                OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
            }
        }

    /**
     * クライアントを削除する
     *
     * @param clientId クライアントID
     * @return 成功時はUnit、失敗時はエラー
     */
    suspend fun delete(clientId: String): Either<OAuthClientError, Unit> = newSuspendedTransaction {
        try {
            // 存在確認
            val existing = OAuthClients.selectAll()
                .where { OAuthClients.clientId eq clientId }
                .firstOrNull()

            if (existing == null) {
                return@newSuspendedTransaction OAuthClientError.NotFound.left()
            }

            // 削除実行
            OAuthClients.deleteWhere { OAuthClients.clientId eq clientId }
            Unit.right()
        } catch (e: Exception) {
            OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * 全てのクライアントを取得する
     *
     * @return クライアントデータのリスト
     */
    suspend fun findAll(): Either<OAuthClientError, List<OAuthClientData>> = newSuspendedTransaction {
        try {
            OAuthClients.selectAll().toList().withRedirectUris().right()
        } catch (e: Exception) {
            OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
        }
    }

    /**
     * 全てのクライアントIDを取得する（Suggestion用）
     * ブロッキング呼び出し用のヘルパーメソッド
     *
     * @return クライアントIDのリスト
     */
    fun getAllClientIdsBlocking(): List<String> = runBlocking {
        try {
            newSuspendedTransaction {
                OAuthClients.selectAll()
                    .map { it[OAuthClients.clientId] }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * リダイレクトURIを登録順（position）付きで一括登録する
     * トランザクション内から呼び出すこと
     */
    private fun insertRedirectUris(clientId: String, redirectUris: List<String>) {
        OAuthClientRedirectUris.batchInsert(redirectUris.withIndex()) { (index, uri) ->
            this[OAuthClientRedirectUris.clientId] = clientId
            this[OAuthClientRedirectUris.position] = index
            this[OAuthClientRedirectUris.redirectUri] = uri
        }
    }

    /**
     * 1クライアント分のリダイレクトURIを登録順で取得する
     * トランザクション内から呼び出すこと
     */
    private fun findRedirectUris(clientId: String): List<String> =
        OAuthClientRedirectUris.selectAll()
            .where { OAuthClientRedirectUris.clientId eq clientId }
            .orderBy(OAuthClientRedirectUris.position)
            .map { it[OAuthClientRedirectUris.redirectUri] }

    /**
     * 複数クライアントの行にリダイレクトURIを付けて変換する
     * クライアントごとにクエリを発行しないよう、URIは1回のクエリでまとめて取得する
     * トランザクション内から呼び出すこと
     */
    private fun List<ResultRow>.withRedirectUris(): List<OAuthClientData> {
        if (isEmpty()) return emptyList()
        val clientIds = map { it[OAuthClients.clientId] }
        val urisByClient = OAuthClientRedirectUris.selectAll()
            .where { OAuthClientRedirectUris.clientId inList clientIds }
            .orderBy(OAuthClientRedirectUris.position)
            .groupBy(
                keySelector = { it[OAuthClientRedirectUris.clientId] },
                valueTransform = { it[OAuthClientRedirectUris.redirectUri] }
            )
        return map { row -> row.toOAuthClientData(urisByClient[row[OAuthClients.clientId]].orEmpty()) }
    }

    /**
     * ResultRowからOAuthClientDataに変換する
     *
     * @param redirectUris 別テーブルから取得したリダイレクトURI
     */
    private fun ResultRow.toOAuthClientData(redirectUris: List<String>): OAuthClientData = OAuthClientData(
        clientId = this[OAuthClients.clientId],
        clientName = this[OAuthClients.clientName],
        clientType = ClientType.fromValue(this[OAuthClients.clientType]) ?: ClientType.PUBLIC,
        redirectUris = redirectUris,
        issuerAccountId = this[OAuthClients.issuerAccountId],
        createdAt = this[OAuthClients.createdAt],
        updatedAt = this[OAuthClients.updatedAt]
    )
}
