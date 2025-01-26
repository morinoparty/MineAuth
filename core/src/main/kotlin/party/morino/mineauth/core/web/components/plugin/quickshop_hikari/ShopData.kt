package party.morino.mineauth.core.web.components.plugin.quickshop_hikari

import kotlinx.serialization.Serializable
import party.morino.mineauth.core.utils.UUIDSerializer
import party.morino.mineauth.core.web.components.bukkit.ItemStackData
import party.morino.mineauth.core.web.components.bukkit.LocationData
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