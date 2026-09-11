package party.morino.mineauth.core.plugin.handler

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import party.morino.mineauth.core.plugin.handler.PlayerActivityHandler.Companion.toIsoOrNull
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * PlayerActivityHandlerの純粋関数部分のテスト
 * Bukkitに依存するエンドポイント本体はMockBukkitが必要なため対象外
 */
class PlayerActivityHandlerTest {

    @Test
    @DisplayName("Formats epoch millis as ISO-8601 UTC")
    fun formatsEpochMillisAsIso() {
        assertEquals("2026-09-11T12:34:56.789Z", 1789130096789L.toIsoOrNull())
    }

    @Test
    @DisplayName("Returns null for zero (never played)")
    fun returnsNullForZero() {
        assertNull(0L.toIsoOrNull())
    }
}
