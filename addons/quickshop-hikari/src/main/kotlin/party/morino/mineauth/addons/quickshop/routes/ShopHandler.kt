package party.morino.mineauth.addons.quickshop.routes

import com.ghostchu.quickshop.api.QuickShopAPI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.entity.Player
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.quickshop.data.ShopData
import party.morino.mineauth.addons.quickshop.data.ShopMode
import party.morino.mineauth.addons.quickshop.utils.coroutines.minecraft
import party.morino.mineauth.api.annotations.AuthedAccessUser
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.Permission
import party.morino.mineauth.api.model.bukkit.ItemStackData
import party.morino.mineauth.api.model.bukkit.LocationData

// plugin名がmineauth-api-quickshop-hikari-addonなので plugins/mineauth-api-quickshop-hikari-addon/shopsにアクセスするとここに飛ぶ
class ShopRoute : KoinComponent {
    val quickShopAPI: QuickShopAPI by inject()

    @GetMapping("/id/:id")
    @Permission("quickshop-hikari.shop")
    suspend fun user(@AuthedAccessUser player: Player, id: String): ShopData? {

        val shops = quickShopAPI.shopManager.allShops.filter {
            it.shopId.toString()==id
        }

        if (shops.isEmpty()) {
            return null
        }

        val shop = shops.first()

        val data = ShopData(
                shopId = shop.shopId,
                owner = if (shop.isUnlimited) null else shop.owner.uniqueId,
                mode = if (shop.isSelling) ShopMode.SELL else ShopMode.BUY,
                stackingAmount = withContext(Dispatchers.minecraft) {
                    shop.shopStackingAmount
                },
                remaining = withContext(Dispatchers.minecraft) {
                    if (shop.isBuying) {
                        shop.remainingStock
                    } else {
                        shop.remainingStock
                    }
                },
                location = LocationData.fromLocation(shop.location),
                price = shop.price,
                item = ItemStackData.fromItemStack(shop.item)
        )
        return data
    }
}