package party.morino.mineauth.core.web.components.plugin.quickshop_hikari

import kotlinx.serialization.Serializable

@Serializable
data class ShopSetting(
        val price: Double,
        val mode: ShopMode,
        val perBulkAmount: Int
)