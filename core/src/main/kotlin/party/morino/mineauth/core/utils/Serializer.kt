package party.morino.mineauth.core.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import party.morino.mineauth.api.utils.UUIDSerializer
import party.morino.mineauth.core.utils.json
import party.morino.mineauth.core.utils.PlayerUtils.toUUID
import java.util.*



// OfflinePlayer <==> UUID
object OfflinePlayerSerializer : KSerializer<OfflinePlayer> {
    override val descriptor = PrimitiveSerialDescriptor("OfflinePlayer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OfflinePlayer {
        val string = decoder.decodeString()
        return if (string.matches(Regex("([0-9a-f]{8})-([0-9a-f]{4})-([0-9a-f]{4})-([0-9a-f]{4})-([0-9a-f]{12})"))) {
            Bukkit.getOfflinePlayer(string.toUUID())
        }else{
            Bukkit.getOfflinePlayer(string)
        }
    }

    override fun serialize(encoder: Encoder, value: OfflinePlayer) {
        encoder.encodeString(value.uniqueId.toString())
    }
}

