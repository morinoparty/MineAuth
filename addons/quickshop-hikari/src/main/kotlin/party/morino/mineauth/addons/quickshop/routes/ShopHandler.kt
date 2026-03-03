package party.morino.mineauth.addons.quickshop.routes

import com.ghostchu.quickshop.api.QuickShopAPI
import com.ghostchu.quickshop.api.shop.Shop
import com.ghostchu.quickshop.api.shop.ShopType
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.quickshop.config.QuickShopConfig
import party.morino.mineauth.addons.quickshop.data.PaginatedShopsResponse
import party.morino.mineauth.addons.quickshop.data.ShopData
import party.morino.mineauth.addons.quickshop.data.ShopMode
import party.morino.mineauth.addons.quickshop.data.ShopSetting
import party.morino.mineauth.addons.quickshop.utils.coroutines.minecraft
import party.morino.mineauth.api.annotations.AuthedAccessUser
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.PathParam
import party.morino.mineauth.api.annotations.PostMapping
import party.morino.mineauth.api.annotations.QueryParams
import party.morino.mineauth.api.annotations.RequestBody
import party.morino.mineauth.api.annotations.TargetPlayer
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import party.morino.mineauth.api.model.bukkit.ItemStackData
import party.morino.mineauth.api.model.bukkit.LocationData

/**
 * QuickShop-Hikariのショップ操作を行うハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class ShopHandler : KoinComponent {
    private val quickShopAPI: QuickShopAPI by inject()
    private val config: QuickShopConfig by inject()

    /**
     * サーバー全体のショップ一覧をカーソルベースページネーションで取得する
     * GET /shops?cursor={shopId}&limit={limit}
     *
     * cursorにはshopIdを指定し、そのIDより後のショップを返す（排他的カーソル）。
     * allShopsでメモリ上の全ショップを1回で取得し、N+1問題を回避する。
     *
     * @param params クエリパラメータ（cursor, limit）
     * @return ページネーション付きショップ一覧
     */
    @GetMapping("/shops")
    suspend fun listAllShops(@QueryParams params: Map<String, String>): PaginatedShopsResponse { // クエリパラメータの解析とバリデーション
        val cursor = parseCursor(params["cursor"])
        val limit = parseLimit(params["limit"])

        // メインスレッドでショップ一覧のスナップショットを取得（スレッドセーフ）
        val fetched = quickShopAPI.shopManager.allShops.asSequence().sortedBy { it.shopId }.let { seq -> // カーソルが指定されている場合、そのIDより後のショップのみ取得
                    if (cursor != null) seq.filter { it.shopId > cursor } else seq
                }.take(limit + 1) // 1件多く取得してhasMoreを判定
                .toList()

        // limit+1件目が存在すれば次のページがある
        val hasMore = fetched.size > limit
        val resultShops = if (hasMore) fetched.dropLast(1) else fetched

        // ShopDataに変換
        val shopDataList = resultShops.map { it.toShopData() }

        // 次のカーソルは結果の最後のshopId
        val nextCursor = if (hasMore) resultShops.lastOrNull()?.shopId?.toString() else null

        return PaginatedShopsResponse(
                shops = shopDataList,
                nextCursor = nextCursor,
                hasMore = hasMore,
        )
    }

    /**
     * ショップ詳細を取得する
     * GET /shops/{shopId}
     */
    @GetMapping("/shops/{shopId}")
    suspend fun getShop(@PathParam("shopId") shopId: String): ShopData {
        val shop = findShopOrThrow(parseShopId(shopId))
        return shop.toShopData()
    }

    // ========================================
    // 認証必須エンドポイント
    // ========================================

    /**
     * プレイヤーのショップ一覧を取得する
     * GET /users/{player}/shops
     *
     * ユーザートークン: 自分のショップのみ取得可能（me/UUID/名前）
     * サービストークン: 任意のプレイヤーのショップを取得可能
     */
    @GetMapping("/users/{player}/shops")
    suspend fun getMyShops(@TargetPlayer player: OfflinePlayer): List<Long> {
        return shopIdsFor(player.uniqueId)
    }

    /**
     * ショップ設定を取得する（オーナーのみ）
     * GET /shops/{shopId}/setting
     */
    @GetMapping("/shops/{shopId}/setting")
    suspend fun getShopSetting(@AuthedAccessUser player: OfflinePlayer, @PathParam("shopId") shopId: String): ShopSetting {
        val shop = findShopOrThrow(parseShopId(shopId))
        ensureOwner(player, shop)
        return shop.toShopSetting()
    }

    /**
     * ショップ設定を更新する（オーナーのみ）
     * POST /shops/{shopId}/setting
     */
    @PostMapping("/shops/{shopId}/setting")
    suspend fun updateShopSetting(@AuthedAccessUser player: OfflinePlayer, @PathParam("shopId") shopId: String, @RequestBody setting: ShopSetting) {
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
        return ShopData(shopId = this.shopId, owner = if (this.isUnlimited) null else this.owner.uniqueId, mode = if (this.isSelling) ShopMode.SELL else ShopMode.BUY, stackingAmount = withContext(Dispatchers.minecraft) {
            this@toShopData.shopStackingAmount
        }, remaining = withContext(Dispatchers.minecraft) {
            getRemaining(this@toShopData)
        }, location = LocationData.fromLocation(this.location), price = this.price, item = ItemStackData.fromItemStack(this.item))
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
     * カーソルパラメータをパースする
     * null/空文字はnull（先頭から開始）として扱う
     */
    private fun parseCursor(cursor: String?): Long? {
        if (cursor.isNullOrBlank()) return null
        return cursor.toLongOrNull() ?: throw HttpError(HttpStatus.BAD_REQUEST, "Invalid cursor format")
    }

    /**
     * limitパラメータをパースし、範囲内に制限する
     */
    private fun parseLimit(limit: String?): Int {
        if (limit.isNullOrBlank()) return config.defaultLimit
        val parsed = limit.toIntOrNull() ?: throw HttpError(HttpStatus.BAD_REQUEST, "Invalid limit format")
        if (parsed <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Limit must be greater than 0")
        }
        return parsed.coerceAtMost(config.maxLimit)
    }

    /**
     * shopIdをLongに変換する
     */
    private fun parseShopId(shopId: String): Long {
        return shopId.toLongOrNull() ?: throw HttpError(HttpStatus.BAD_REQUEST, "Invalid shop id")
    }

    /**
     * 指定ユーザーのショップID一覧を取得する
     *
     * @param ownerUuid ショップオーナーのUUID
     * @return ショップIDのリスト
     */
    private fun shopIdsFor(ownerUuid: UUID): List<Long> {
        return quickShopAPI.shopManager.allShops.asSequence().filter { it.owner.uniqueId == ownerUuid }.map { it.shopId }.toList()
    }

    /**
     * Shopを取得する（存在しない場合は404）
     */
    private fun findShopOrThrow(shopId: Long): Shop {
        return quickShopAPI.shopManager.getShop(shopId) ?: throw HttpError(HttpStatus.NOT_FOUND, "Shop not found")
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
        return ShopSetting(price = this.price, mode = if (this.isBuying) ShopMode.BUY else ShopMode.SELL, perBulkAmount = this.shopStackingAmount)
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
            throw HttpError(HttpStatus.BAD_REQUEST, "perBulkAmount must be less than or equal to $maxStackSize")
        }
    }
}
