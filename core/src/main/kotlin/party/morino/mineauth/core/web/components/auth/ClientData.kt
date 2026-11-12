package party.morino.mineauth.core.web.components.auth

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.koin.core.component.KoinComponent
import party.morino.mineauth.core.repository.ClientType
import party.morino.mineauth.core.repository.OAuthClientError
import party.morino.mineauth.core.repository.OAuthClientRepository
import party.morino.mineauth.core.utils.Argon2Utils
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.sql.SQLException
import party.morino.mineauth.core.database.OAuthClients as OAuthClientsTable

/**
 * OAuthクライアントデータのシールドクラス
 * Public/Confidentialの両タイプをサポート
 */
@Serializable(with = ClientDataSerializer::class)
sealed class ClientData : KoinComponent {
    abstract val clientId: String
    abstract val clientName: String
    // 登録済みのリダイレクトURI（いずれかに一致すれば認可を許可する）
    abstract val redirectUris: List<String>

    /**
     * Publicクライアント（シークレットなし）
     */
    @Serializable
    data class PublicClientData(
        override val clientId: String,
        override val clientName: String,
        override val redirectUris: List<String>
    ) : ClientData()

    /**
     * Confidentialクライアント（シークレットあり）
     * hashedClientSecretはArgon2idでハッシュ化されている
     * introspectionAllowedはRFC 7662 Section 4の「リソースサーバーとして個別に認可されたか」を表す
     * （Publicクライアントはイントロスペクションエンドポイントで認証できないため、この項目を持たない）
     */
    @Serializable
    data class ConfidentialClientData(
        override val clientId: String,
        override val clientName: String,
        override val redirectUris: List<String>,
        val hashedClientSecret: String,
        val introspectionAllowed: Boolean = false
    ) : ClientData() {
        /**
         * クライアントシークレットを検証する
         * Argon2idによる定数時間比較で検証
         *
         * @param secret 検証する平文のシークレット
         * @return 一致する場合true
         */
        fun verifySecret(secret: String): Boolean {
            return Argon2Utils.verifySecret(secret, hashedClientSecret)
        }
    }

    companion object : KoinComponent {
        /**
         * クライアントIDでクライアントデータを取得する（データベースから）
         * リポジトリがDBの読み取りの失敗を返した場合も、見つからない場合と同じくnullを返す。
         * DBの障害を存在しないクライアントと区別する必要がある場合（クライアント認証など）はfindClientDataInDbを使うこと
         *
         * @param clientId クライアントID
         * @return クライアントデータ、見つからない場合はnull
         */
        suspend fun getClientDataFromDb(clientId: String): ClientData? = readClientData(clientId).getOrNull()

        /**
         * クライアントIDでクライアントデータを取得し、DBを読めなかった場合と存在しない場合を区別する
         *
         * クライアント認証で障害を存在しないclient_idとして扱うと、障害の間に届いた全てのリクエストが
         * 認証情報の推測として数えられ、正規のクライアントの接続元がブロックされてしまう。
         * DBの例外だけを捕捉し、それ以外の予期しない例外は呼び出し元へ伝える
         *
         * @param clientId クライアントID
         * @return 見つかった場合はクライアントデータ、存在しない場合はnullを持つRight。DBを読めなかった場合はLeft
         */
        suspend fun findClientDataInDb(clientId: String): Either<OAuthClientError, ClientData?> = try {
            readClientData(clientId).fold(
                // 存在しないことを確認できた場合だけをnullにし、それ以外の失敗は読み取りの失敗として返す
                ifLeft = { error -> if (error is OAuthClientError.NotFound) null.right() else error.left() },
                ifRight = { clientData -> clientData.right() }
            )
        } catch (e: SQLException) {
            // トランザクションの開始・終了と、シークレットハッシュの読み込みで起きたDBの例外はリポジトリの外へ投げられる
            OAuthClientError.DatabaseError(e.message ?: "Unknown error").left()
        }

        /**
         * DBからクライアントデータを読み込む
         * リポジトリが返した失敗はLeftのまま返し、リポジトリの外で起きたDBの例外はそのまま投げる
         *
         * @param clientId クライアントID
         * @return クライアントデータ（Confidentialでシークレットハッシュが無い場合はnull）、またはリポジトリの失敗
         */
        private suspend fun readClientData(clientId: String): Either<OAuthClientError, ClientData?> =
            OAuthClientRepository.findById(clientId).map { client ->
                when (client.clientType) {
                    ClientType.PUBLIC -> PublicClientData(
                        clientId = client.clientId,
                        clientName = client.clientName,
                        redirectUris = client.redirectUris
                    )

                    ClientType.CONFIDENTIAL -> {
                        // Confidentialクライアントの場合、シークレットハッシュを取得
                        // 2回の読み込みの間に削除された場合は、存在しないクライアントとして扱う
                        getClientSecretHash(clientId)?.let { secretHash ->
                            ConfidentialClientData(
                                clientId = client.clientId,
                                clientName = client.clientName,
                                redirectUris = client.redirectUris,
                                hashedClientSecret = secretHash,
                                introspectionAllowed = client.introspectionAllowed
                            )
                        }
                    }
                }
            }

        /**
         * クライアントIDでクライアントデータを取得する
         *
         * @param clientId クライアントID
         * @return クライアントデータ
         * @throws IllegalStateException クライアントが見つからない場合
         */
        fun getClientData(clientId: String): ClientData {
            return runBlocking { getClientDataFromDb(clientId) }
                ?: throw IllegalStateException("Client not found: $clientId")
        }

        /**
         * クライアントシークレットを検証する
         *
         * @param clientId クライアントID
         * @param clientSecret 検証するシークレット
         * @return 検証成功時はクライアントデータ、失敗時はエラー
         */
        suspend fun verifyClientSecret(
            clientId: String,
            clientSecret: String
        ): Either<OAuthClientError, ClientData> {
            return OAuthClientRepository.verifyClientSecret(clientId, clientSecret).map { client ->
                ConfidentialClientData(
                    clientId = client.clientId,
                    clientName = client.clientName,
                    redirectUris = client.redirectUris,
                    hashedClientSecret = "", // 検証後は不要
                    introspectionAllowed = client.introspectionAllowed
                )
            }
        }

        /**
         * クライアントシークレットハッシュを取得する（内部用）
         * DBを読めなかった場合は例外を投げる（findClientDataInDbはこれを読み取りの失敗として返す）
         *
         * @param clientId クライアントID
         * @return シークレットハッシュ。行かハッシュが無い場合はnull
         * @throws SQLException DBを読めなかった場合
         */
        private suspend fun getClientSecretHash(clientId: String): String? {
            // OAuthClientsテーブルから直接シークレットハッシュを取得
            return newSuspendedTransaction {
                OAuthClientsTable.selectAll()
                    .where { OAuthClientsTable.clientId eq clientId }
                    .firstOrNull()
                    ?.get(OAuthClientsTable.clientSecretHash)
            }
        }
    }
}

/**
 * ClientDataのJSONシリアライザ
 * clientSecretの有無でPublic/Confidentialを判別
 */
object ClientDataSerializer : JsonContentPolymorphicSerializer<ClientData>(ClientData::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ClientData> {
        val obj = element.jsonObject
        return if (obj["clientSecret"] != null || obj["hashedClientSecret"] != null) {
            ClientData.ConfidentialClientData.serializer()
        } else {
            ClientData.PublicClientData.serializer()
        }
    }
}
