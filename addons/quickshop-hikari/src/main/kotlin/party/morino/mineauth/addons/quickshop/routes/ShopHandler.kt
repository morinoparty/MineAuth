package party.morino.mineauth.addons.quickshop.routes

import com.ghostchu.quickshop.api.QuickShopAPI
import com.ghostchu.quickshop.api.shop.Shop
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
import party.morino.mineauth.addons.quickshop.paging.ShopPage
import party.morino.mineauth.addons.quickshop.stock.ShopStock
import party.morino.mineauth.addons.quickshop.stock.ShopStockCache
import party.morino.mineauth.addons.quickshop.stock.ShopStockResolver
import party.morino.mineauth.addons.quickshop.stock.toRemaining
import party.morino.mineauth.addons.quickshop.utils.coroutines.async
import party.morino.mineauth.addons.quickshop.utils.coroutines.minecraft
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.Body
import party.morino.mineauth.api.annotations.Caller
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.Path
import party.morino.mineauth.api.annotations.PlayerParam
import party.morino.mineauth.api.annotations.Post
import party.morino.mineauth.api.annotations.Public
import party.morino.mineauth.api.annotations.Query
import party.morino.mineauth.api.auth.Principal
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
    private val stockResolver: ShopStockResolver by inject()
    private val stockCache: ShopStockCache by inject()

    companion object {
        /** QuickShop-Hikari 6.3.0.x で ShopManager に登録される買取ショップ種別の識別子（BuyingType#identifier） */
        private const val SHOP_TYPE_BUYING = "BUYING"

        /** QuickShop-Hikari 6.3.0.x で ShopManager に登録される販売ショップ種別の識別子（SellingType#identifier） */
        private const val SHOP_TYPE_SELLING = "SELLING"
    }

    /**
     * サーバー全体のショップ一覧をカーソルベースページネーションで取得する
     * GET /shops?cursor={shopId}&limit={limit}
     *
     * cursorにはshopIdを指定し、そのIDより後のショップを返す（排他的カーソル）。
     * レスポンスの nextCursor を次の cursor に渡すと、hasMore が false になるまでで全ショップを辿れる。
     * 在庫・空き容量はこのページのショップだけを数える（[ShopStockResolver]）。
     *
     * @param cursor カーソル（このshopIdより後のショップを返す。省略時は先頭から）
     * @param limit 取得件数（省略時はデフォルト値、最大値でクランプされる）
     * @return ページネーション付きショップ一覧
     */
    @Get("/shops")
    @Public
    suspend fun listAllShops(@Query("cursor") cursor: Long?, @Query("limit") limit: Int?): PaginatedShopsResponse =
        // limitパラメータのバリデーションと範囲制限（不正な値はスレッドを切り替える前に400で返す）
        // ハンドラーのコンテキストにはディスパッチャが無く、メインスレッドから戻った後もメインスレッドで続いてしまうため、
        // 非同期スレッドで実行し、メインスレッドが要る処理だけを切り替える
        resolveLimit(limit).let { resolvedLimit ->
            withContext(Dispatchers.async) {
                // ショップ一覧のスナップショットはメインスレッドで取得する
                val allShops = withContext(Dispatchers.minecraft) { quickShopAPI.shopManager.allShops.toList() }
                val page = ShopPage.of(allShops, { it.shopId }, cursor, resolvedLimit)

                PaginatedShopsResponse(
                    shops = toShopDataList(page.items),
                    nextCursor = page.nextCursor?.toString(),
                    hasMore = page.hasMore,
                )
            }
        }

    /**
     * ショップ詳細を取得する
     * GET /shops/{shopId}
     */
    @Get("/shops/{shopId}")
    @Public
    suspend fun getShop(@Path("shopId") shopId: Long): ShopData =
        withContext(Dispatchers.async) {
            val shop = findShopOrThrow(shopId)
            toShopDataList(listOf(shop)).single()
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
    @Get("/users/{player}/shops")
    @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun getMyShops(@PlayerParam("player") player: OfflinePlayer): List<Long> {
        return shopIdsFor(player.uniqueId)
    }

    /**
     * ショップ設定を取得する（オーナーのみ）
     * GET /shops/{shopId}/setting
     */
    @Get("/shops/{shopId}/setting")
    @Authenticated
    suspend fun getShopSetting(@Caller caller: Principal.User, @Path("shopId") shopId: Long): ShopSetting {
        val shop = findShopOrThrow(shopId)
        ensureOwner(caller.offlinePlayer, shop)
        return shop.toShopSetting()
    }

    /**
     * ショップ設定を更新する（オーナーのみ）
     * POST /shops/{shopId}/setting
     */
    @Post("/shops/{shopId}/setting")
    @Authenticated
    suspend fun updateShopSetting(@Caller caller: Principal.User, @Path("shopId") shopId: Long, @Body setting: ShopSetting) {
        val shop = findShopOrThrow(shopId)
        ensureOwner(caller.offlinePlayer, shop)
        validateShopSetting(shop, setting)

        // 設定の適用は看板の更新などでワールドに触れるため、メインスレッドで行う
        withContext(Dispatchers.minecraft) {
            // price()/price(U) はスター投影下で型情報が失われるため、Double で扱える getPrice/setPrice を意図的に使う
            shop.price = setting.price
            // 6.3.0系でShopType(enum)は廃止され、ShopManagerに登録済みのIShopTypeを識別子で解決する方式になった
            val shopTypeId = if (setting.mode == ShopMode.BUY) SHOP_TYPE_BUYING else SHOP_TYPE_SELLING
            shop.shopType(quickShopAPI.shopManager.shopTypeOrDefault(shopTypeId))

            // アイテムの個数を更新
            val pendingItemStack = shop.item.clone()
            pendingItemStack.amount = setting.perBulkAmount
            shop.item = pendingItemStack
        }

        // 販売・買取や取引単位が変わると在庫の数え方も変わるので、使い回している結果を捨てる
        stockCache.invalidate(shopId)
    }

    // ========================================
    // ヘルパーメソッド
    // ========================================

    /**
     * ShopをShopDataに変換する
     * 在庫・空き容量を数えてから、ショップの情報の読み出しはメインスレッドでまとめて行う（ショップごとにスレッドを切り替えない）
     *
     * @param shops 変換するショップ
     * @return 同じ順のShopData
     */
    private suspend fun toShopDataList(shops: List<Shop<*, *>>): List<ShopData> {
        if (shops.isEmpty()) return emptyList()
        val stocks = stockResolver.resolve(shops)
        return withContext(Dispatchers.minecraft) {
            shops.map { shop -> shop.toShopData(stocks[shop.shopId] ?: ShopStock.Unknown) }
        }
    }

    /**
     * ShopをShopDataに変換する（メインスレッドで呼ぶ）
     *
     * @param stock 数えた在庫・空き容量
     */
    private fun Shop<*, *>.toShopData(stock: ShopStock): ShopData {
        return ShopData(
            shopId = shopId,
            owner = if (isUnlimited) null else owner.uniqueId,
            mode = if (isSelling) ShopMode.SELL else ShopMode.BUY,
            stackingAmount = shopStackingAmount,
            remaining = stock.toRemaining(),
            unlimited = stock == ShopStock.Unlimited,
            location = LocationData.fromLocation(bukkitLocation()),
            price = price,
            item = ItemStackData.fromItemStack(item),
        )
    }

    /**
     * アイテムの最大スタック数を取得する
     */
    private fun getItemMaxStackSize(material: Material): Int {
        return material.maxStackSize
    }

    /**
     * limitパラメータを解決し、範囲内に制限する
     * 省略時はデフォルト値を使用する
     */
    private fun resolveLimit(limit: Int?): Int {
        if (limit == null) return config.defaultLimit
        if (limit <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Limit must be greater than 0")
        }
        return limit.coerceAtMost(config.maxLimit)
    }

    /**
     * 指定ユーザーのショップID一覧を取得する
     *
     * @param ownerUuid ショップオーナーのUUID
     * @return ショップIDのリスト
     */
    private fun shopIdsFor(ownerUuid: UUID): List<Long> {
        // inline の List 操作にし、ラムダをオブジェクトとして標準ライブラリへ渡さない（/shops の cursor と同じ 500 を避ける）
        return quickShopAPI.shopManager.allShops.filter { it.owner.uniqueId == ownerUuid }.map { it.shopId }
    }

    /**
     * Shopを取得する（存在しない場合は404）
     */
    private fun findShopOrThrow(shopId: Long): Shop<*, *> {
        return quickShopAPI.shopManager.getShop(shopId) ?: throw HttpError(HttpStatus.NOT_FOUND, "Shop not found")
    }

    /**
     * ショップのオーナーかどうかを検証する
     */
    private fun ensureOwner(player: OfflinePlayer, shop: Shop<*, *>) {
        if (shop.owner.uniqueId != player.uniqueId) {
            throw HttpError(HttpStatus.FORBIDDEN, "You are not the owner of this shop")
        }
    }

    /**
     * ShopからShopSettingを生成する
     */
    private fun Shop<*, *>.toShopSetting(): ShopSetting {
        return ShopSetting(price = this.price, mode = if (this.isBuying) ShopMode.BUY else ShopMode.SELL, perBulkAmount = this.shopStackingAmount)
    }

    /**
     * ショップ設定のバリデーション
     */
    private fun validateShopSetting(shop: Shop<*, *>, setting: ShopSetting) {
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
