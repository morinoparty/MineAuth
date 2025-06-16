package party.morino.mineauth.addons.quickshop.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import party.morino.mineauth.api.model.bukkit.ItemStackData
import party.morino.mineauth.api.model.bukkit.LocationData
import java.util.*

@Serializable
data class ShopData(
    val shopId: Long,
    val owner: @Serializable(with = UUIDSerializer::class) UUID?,
    val mode : ShopMode,
    val stackingAmount : Int,
    val remaining : Int,
    val location : LocationData,
    val price: Double,
    val item: ItemStackData,
)