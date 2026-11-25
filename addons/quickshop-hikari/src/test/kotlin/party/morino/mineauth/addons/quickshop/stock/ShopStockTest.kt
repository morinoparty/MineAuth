package party.morino.mineauth.addons.quickshop.stock

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * ShopStockの買取数の計算・remainingへの変換、ChunkKeyの読み込み対象の選択のテスト
 */
class ShopStockTest {

    @Test
    @DisplayName("Buying shops are limited by what the owner can afford")
    fun buyableLimitedByBalance() {
        // 空き 100 回分、1 回 10 で所持金 255 → 25 回分しか払えない
        assertEquals(ShopStock.Counted(25), ShopStock.buyable(space = 100, price = 10.0, balance = 255.0))
        // 所持金が十分なら空き容量で決まる
        assertEquals(ShopStock.Counted(100), ShopStock.buyable(space = 100, price = 10.0, balance = 1e9))
    }

    @Test
    @DisplayName("Buying shops use only the space when the balance is unknown")
    fun buyableWithoutBalance() {
        assertEquals(ShopStock.Counted(100), ShopStock.buyable(space = 100, price = 10.0, balance = null))
        assertEquals(ShopStock.Counted(0), ShopStock.buyable(space = 100, price = 10.0, balance = -5.0))
    }

    @Test
    @DisplayName("Maps stocks to the remaining field")
    fun mapsToRemaining() {
        assertEquals(-1, ShopStock.Unlimited.toRemaining())
        assertEquals(64, ShopStock.Counted(64).toRemaining())
        assertNull(ShopStock.Unknown.toRemaining())
    }

    @Test
    @DisplayName("Selects distinct chunks in order up to the limit")
    fun selectsChunksUpToLimit() {
        val world = UUID.randomUUID()
        val a = ChunkKey(world, 0, 0)
        val b = ChunkKey(world, 1, 0)
        val c = ChunkKey(world, 2, 0)

        assertEquals(setOf(a, b), ChunkKey.selectToLoad(listOf(a, a, b, c), maxLoads = 2))
        assertEquals(emptySet(), ChunkKey.selectToLoad(listOf(a, b), maxLoads = 0))
    }
}
