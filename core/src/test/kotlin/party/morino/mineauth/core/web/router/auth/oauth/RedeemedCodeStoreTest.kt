package party.morino.mineauth.core.web.router.auth.oauth

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RedeemedCodeStoreのテスト
 * 使用済みの認可コードの記録が本来の有効期限まで参照でき、期限後に削除されることを確認する
 */
class RedeemedCodeStoreTest {

    private val startTime = 1_000_000L
    private val redeemed = RedeemedCode(grantId = "grant", clientId = "client", expiresAt = startTime + 600_000L)

    @Test
    @DisplayName("A record is found until the original expiry of the code")
    fun recordIsFoundUntilExpiry() {
        var now = startTime
        val store = RedeemedCodeStore(clock = { now })
        store.record("code", redeemed)

        assertEquals(redeemed, store.find("code"))
        assertNull(store.find("unknown"))

        now = redeemed.expiresAt
        assertEquals(redeemed, store.find("code"))

        now = redeemed.expiresAt + 1
        assertNull(store.find("code"))
    }

    @Test
    @DisplayName("Cleanup drops only expired records and is throttled")
    fun cleanupDropsExpiredRecordsWithThrottling() {
        var now = startTime
        val store = RedeemedCodeStore(clock = { now })
        store.record("expiring", redeemed.copy(expiresAt = startTime + 1_000L))
        store.record("lasting", redeemed)

        // 最初の実行は期限切れがないので何も削除しない
        store.cleanupExpired()
        assertEquals(2, store.size)

        // 最小間隔の内側では、期限切れの記録があっても走査しない
        now = startTime + 30_000L
        store.cleanupExpired()
        assertEquals(2, store.size)

        // 最小間隔を過ぎた次の実行で、期限切れの記録だけを削除する
        now = startTime + 60_000L
        store.cleanupExpired()
        assertEquals(1, store.size)
        assertEquals(redeemed, store.find("lasting"))
    }
}
