package party.morino.mineauth.addons.quickshop.stock

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ShopStockCacheのテスト
 * 時刻は差し替えた時計で進める
 */
class ShopStockCacheTest {

    // テストから進める現在時刻（ミリ秒）
    private var now = 0L

    private val cache = ShopStockCache(ttlMillis = 120_000, clock = { now })

    @Test
    @DisplayName("Returns a counted stock until the ttl expires")
    fun returnsStockUntilExpired() {
        cache.put(1L, ShopStock.Counted(64))

        now = 119_999
        assertEquals(ShopStock.Counted(64), cache.get(1L))

        now = 120_000
        assertNull(cache.get(1L))
    }

    @Test
    @DisplayName("Does not keep unknown stocks so they are counted on the next request")
    fun doesNotKeepUnknown() {
        cache.put(1L, ShopStock.Unknown)

        assertNull(cache.get(1L))
    }

    @Test
    @DisplayName("Forgets a shop when invalidated")
    fun forgetsInvalidatedShop() {
        cache.put(1L, ShopStock.Unlimited)
        cache.invalidate(1L)

        assertNull(cache.get(1L))
    }

    @Test
    @DisplayName("Keeps nothing when the ttl is zero")
    fun keepsNothingWithZeroTtl() {
        val disabled = ShopStockCache(ttlMillis = 0, clock = { now })
        disabled.put(1L, ShopStock.Counted(1))

        assertNull(disabled.get(1L))
    }
}
