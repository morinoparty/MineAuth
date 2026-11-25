package party.morino.mineauth.addons.quickshop.paging

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ShopPage.ofのテスト
 * ショップの代わりに ID（Long）そのものを要素として使う
 */
class ShopPageTest {

    // 順不同・飛び番の ID（本番のショップIDと同じく連番とは限らない）
    private val ids = listOf(30L, 16L, 17L, 45L, 21L)

    private fun page(cursor: Long?, limit: Int) = ShopPage.of(ids, { it }, cursor, limit)

    @Test
    @DisplayName("Returns the first page sorted by id with a cursor to the next page")
    fun returnsFirstPage() {
        val first = page(cursor = null, limit = 2)

        assertEquals(listOf(16L, 17L), first.items)
        assertEquals(17L, first.nextCursor)
        assertTrue(first.hasMore)
    }

    @Test
    @DisplayName("Returns shops after the cursor exclusively")
    fun returnsShopsAfterCursor() {
        val next = page(cursor = 17L, limit = 2)

        assertEquals(listOf(21L, 30L), next.items)
        assertEquals(30L, next.nextCursor)
    }

    @Test
    @DisplayName("Ends without a cursor on the last page")
    fun endsOnLastPage() {
        val last = page(cursor = 21L, limit = 2)

        assertEquals(listOf(30L, 45L), last.items)
        assertNull(last.nextCursor)
        assertFalse(last.hasMore)
    }

    @Test
    @DisplayName("Returns an empty page for a cursor beyond the last id")
    fun returnsEmptyPageBeyondLastId() {
        val empty = page(cursor = 99_999_999L, limit = 10)

        assertEquals(emptyList(), empty.items)
        assertNull(empty.nextCursor)
        assertFalse(empty.hasMore)
    }

    @Test
    @DisplayName("Following nextCursor visits every id exactly once")
    fun iteratesThroughAllIds() {
        val visited = mutableListOf<Long>()
        var cursor: Long? = null
        do {
            val current = page(cursor, limit = 2)
            visited += current.items
            cursor = current.nextCursor
        } while (current.hasMore)

        assertEquals(ids.sorted(), visited)
    }
}
