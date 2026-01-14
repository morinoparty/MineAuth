package party.morino.mineauth.addons.quickshop.routes

import com.ghostchu.quickshop.api.QuickShopAPI
import com.ghostchu.quickshop.api.shop.Shop
import com.ghostchu.quickshop.api.shop.ShopType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.quickshop.data.ShopData
import party.morino.mineauth.addons.quickshop.data.ShopMode
import party.morino.mineauth.addons.quickshop.data.ShopSetting
import party.morino.mineauth.addons.quickshop.utils.coroutines.minecraft
import party.morino.mineauth.api.annotations.*
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import party.morino.mineauth.api.model.bukkit.ItemStackData
import party.morino.mineauth.api.model.bukkit.LocationData
import java.util.*

/**
 * QuickShop-Hikariのショップ操作を行うハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class ShopHandler : KoinComponent {
    private val quickShopAPI: QuickShopAPI by inject()

    // ========================================
    // 認証不要エンドポイント
    // ========================================

    /**
     * ショップ詳細を取得する
     * GET /shops/{shopId}
     */
    @GetMapping("/shops/{shopId}")
    suspend fun getShop(@PathParam("shopId") shopId: String): ShopData {
        val shop = findShopOrThrow(parseShopId(shopId))
        return shop.toShopData()
    }

    /**
     * 指定ユーザーのショップ一覧を取得する
     * GET /users/{uuid}/shops
     */
    @GetMapping("/users/{uuid}/shops")
    suspend fun getUserShops(@PathParam("uuid") uuid: String): List<Long> {
        val playerUuid = try {
            UUID.fromString(uuid)
        } catch (e: IllegalArgumentException) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Invalid UUID format")
        }

        return quickShopAPI.shopManager.allShops.asSequence()
            .filter { it.owner.uniqueId == playerUuid }
            .map { it.shopId }
            .toList()
    }

    // ========================================
    // 認証必須エンドポイント
    // ========================================

    /**
     * 自分のショップ一覧を取得する
     * GET /users/me/shops
     */
    @GetMapping("/users/me/shops")
    suspend fun getMyShops(@AuthedAccessUser player: OfflinePlayer): List<Long> {
        return quickShopAPI.shopManager.allShops.asSequence()
            .filter { it.owner.uniqueId == player.uniqueId }
            .map { it.shopId }
            .toList()
    }

    /**
     * ショップ設定を取得する（オーナーのみ）
     * GET /shops/{shopId}/setting
     */
    @GetMapping("/shops/{shopId}/setting")
    suspend fun getShopSetting(
        @AuthedAccessUser player: OfflinePlayer,
        @PathParam("shopId") shopId: String
    ): ShopSetting {
        val shop = findShopOrThrow(parseShopId(shopId))
        ensureOwner(player, shop)
        return shop.toShopSetting()
    }

    /**
     * ショップ設定を更新する（オーナーのみ）
     * POST /shops/{shopId}/setting
     */
    @PostMapping("/shops/{shopId}/setting")
    suspend fun updateShopSetting(
        @AuthedAccessUser player: OfflinePlayer,
        @PathParam("shopId") shopId: String,
        @RequestBody setting: ShopSetting
    ) {
        val shop = findShopOrThrow(parseShopId(shopId))
        ensureOwner(player, shop)
        validateShopSetting(shop, setting)

        // 設定を適用
        shop.price = setting.price
        shop.shopType = if (setting.mode == ShopMode.BUY) ShopType.BUYING else ShopType.SELLING

        // アイテムの個数を更新
        val pendingItemStack = shop.item.clone()
        pendingItemStack.amount = setting.perBulkAmount
        shop.item = pendingItemStack
    }

    // ========================================
    // ヘルパーメソッド
    // ========================================

    /**
     * ShopをShopDataに変換する
     */
    private suspend fun Shop.toShopData(): ShopData {
        return ShopData(
            shopId = this.shopId,
            owner = if (this.isUnlimited) null else this.owner.uniqueId,
            mode = if (this.isSelling) ShopMode.SELL else ShopMode.BUY,
            stackingAmount = withContext(Dispatchers.minecraft) {
                this@toShopData.shopStackingAmount
            },
            remaining = withContext(Dispatchers.minecraft) {
                getRemaining(this@toShopData)
            },
            location = LocationData.fromLocation(this.location),
            price = this.price,
            item = ItemStackData.fromItemStack(this.item)
        )
    }

    /**
     * ショップの残り在庫/空き容量を取得する
     */
    private fun getRemaining(shop: Shop): Int {
        return if (shop.isBuying) {
            shop.remainingSpace
        } else {
            shop.remainingStock
        }
    }

    /**
     * アイテムの最大スタック数を取得する
     */
    private fun getItemMaxStackSize(material: Material): Int {
        return material.maxStackSize
    }

    /**
     * shopIdをLongに変換する
     */
    private fun parseShopId(shopId: String): Long {
        return shopId.toLongOrNull()
            ?: throw HttpError(HttpStatus.BAD_REQUEST, "Invalid shop id")
    }

    /**
     * Shopを取得する（存在しない場合は404）
     */
    private fun findShopOrThrow(shopId: Long): Shop {
        return quickShopAPI.shopManager.getShop(shopId)
            ?: throw HttpError(HttpStatus.NOT_FOUND, "Shop not found")
    }

    /**
     * ショップのオーナーかどうかを検証する
     */
    private fun ensureOwner(player: OfflinePlayer, shop: Shop) {
        if (shop.owner.uniqueId != player.uniqueId) {
            throw HttpError(HttpStatus.FORBIDDEN, "You are not the owner of this shop")
        }
    }

    /**
     * ShopからShopSettingを生成する
     */
    private fun Shop.toShopSetting(): ShopSetting {
        return ShopSetting(
            price = this.price,
            mode = if (this.isBuying) ShopMode.BUY else ShopMode.SELL,
            perBulkAmount = this.shopStackingAmount
        )
    }

    /**
     * ショップ設定のバリデーション
     */
    private fun validateShopSetting(shop: Shop, setting: ShopSetting) {
        if (setting.price <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Price must be greater than 0")
        }

        val maxStackSize = getItemMaxStackSize(shop.item.type)
        if (setting.perBulkAmount <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "perBulkAmount must be greater than 0")
        }
        if (setting.perBulkAmount > maxStackSize) {
            throw HttpError(
                HttpStatus.BAD_REQUEST,
                "perBulkAmount must be less than or equal to $maxStackSize"
            )
        }
    }
}
