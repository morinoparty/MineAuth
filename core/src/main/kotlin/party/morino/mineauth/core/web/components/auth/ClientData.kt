package party.morino.mineauth.core.web.components.auth

import arrow.core.Either
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
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import party.morino.mineauth.core.database.OAuthClients as OAuthClientsTable

/**
 * OAuthクライアントデータのシールドクラス
 * Public/Confidentialの両タイプをサポート
 */
@Serializable(with = ClientDataSerializer::class)
sealed class ClientData : KoinComponent {
    abstract val clientId: String
    abstract val clientName: String
    abstract val redirectUri: String

    /**
     * Publicクライアント（シークレットなし）
     */
    @Serializable
    data class PublicClientData(
        override val clientId: String,
        override val clientName: String,
        override val redirectUri: String
    ) : ClientData()

    /**
     * Confidentialクライアント（シークレットあり）
     * hashedClientSecretはArgon2idでハッシュ化されている
     */
    @Serializable
    data class ConfidentialClientData(
        override val clientId: String,
        override val clientName: String,
        override val redirectUri: String,
        val hashedClientSecret: String
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
         *
         * @param clientId クライアントID
         * @return クライアントデータ、見つからない場合はnull
         */
        suspend fun getClientDataFromDb(clientId: String): ClientData? {
            return when (val result = OAuthClientRepository.findById(clientId)) {
                is Either.Right -> {
                    val client = result.value
                    when (client.clientType) {
                        ClientType.PUBLIC -> PublicClientData(
                            clientId = client.clientId,
                            clientName = client.clientName,
                            redirectUri = client.redirectUri
                        )

                        ClientType.CONFIDENTIAL -> {
                            // Confidentialクライアントの場合、シークレットハッシュを取得
                            val secretHash = getClientSecretHash(clientId)
                            if (secretHash != null) {
                                ConfidentialClientData(
                                    clientId = client.clientId,
                                    clientName = client.clientName,
                                    redirectUri = client.redirectUri,
                                    hashedClientSecret = secretHash
                                )
                            } else {
                                null
                            }
                        }
                    }
                }

                is Either.Left -> null
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
                    redirectUri = client.redirectUri,
                    hashedClientSecret = "" // 検証後は不要
                )
            }
        }

        /**
         * クライアントシークレットハッシュを取得する（内部用）
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
