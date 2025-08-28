package party.morino.mineauth.core.web.components.auth

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.core.MineAuth
import party.morino.mineauth.api.utils.json

@Serializable(with = ClientDataSerializer::class)
sealed class ClientData: KoinComponent {
    abstract val clientId: String
    abstract val clientName: String
    abstract val redirectUri: String

    @Serializable
    data class PublicClientData(
        override val clientId: String, override val clientName: String, override val redirectUri: String
    ): ClientData()

    @Serializable
    data class ConfidentialClientData(
        override val clientId: String, override val clientName: String, override val redirectUri: String, val hashedClientSecret: String
    ): ClientData()

    companion object : KoinComponent{
        private val plugin by inject<MineAuth>()
        fun getClientData(clientId: String): ClientData {
            val file = plugin.dataFolder.resolve("clients").resolve(clientId).resolve("data.json")
            return json.decodeFromString(file.readText())
        }
    }
}

object ClientDataSerializer : JsonContentPolymorphicSerializer<ClientData>(ClientData::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<ClientData> {
        val obj = element.jsonObject
        return if (obj["clientSecret"] != null) {
            ClientData.ConfidentialClientData.serializer()
        } else {
            ClientData.PublicClientData.serializer()
        }
    }
}


