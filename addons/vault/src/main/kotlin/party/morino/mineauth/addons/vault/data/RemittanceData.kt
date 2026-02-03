package party.morino.mineauth.addons.vault.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import java.util.UUID

/**
 * 送金リクエストのデータクラス
 *
 * @property target 送金先プレイヤー（UUIDまたはプレイヤー名で指定可能）
 * @property amount 送金額
 */
@Serializable
data class RemittanceData(
    val target: @Serializable(with = OfflinePlayerSerializer::class) OfflinePlayer,
    val amount: Double
)

/**
 * OfflinePlayerのシリアライザー
 * UUIDまたはプレイヤー名からOfflinePlayerに変換する
 */
object OfflinePlayerSerializer : KSerializer<OfflinePlayer> {
    override val descriptor = PrimitiveSerialDescriptor("OfflinePlayer", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): OfflinePlayer {
        val string = decoder.decodeString()
        // UUIDとしてパースを試み、失敗した場合はプレイヤー名として扱う
        // 大文字/小文字のUUIDどちらにも対応
        val uuid = runCatching { UUID.fromString(string) }.getOrNull()
        return if (uuid != null) {
            Bukkit.getOfflinePlayer(uuid)
        } else {
            // プレイヤー名から取得
            @Suppress("DEPRECATION")
            Bukkit.getOfflinePlayer(string)
        }
    }

    override fun serialize(encoder: Encoder, value: OfflinePlayer) {
        encoder.encodeString(value.uniqueId.toString())
    }
}
