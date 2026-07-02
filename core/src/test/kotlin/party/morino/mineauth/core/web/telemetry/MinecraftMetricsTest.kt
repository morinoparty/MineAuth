package party.morino.mineauth.core.web.telemetry

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.bukkit.Bukkit
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * MinecraftMetricsのテスト
 * Bukkitの静的メソッドをmockkでモックしてゲージの登録と値を検証する
 * （MockBukkitはpaper-apiとのバージョン不整合があるため使用しない）
 */
class MinecraftMetricsTest {

    @BeforeEach
    fun setUp() {
        mockkStatic(Bukkit::class)
        every { Bukkit.getOnlinePlayers() } returns emptyList()
        every { Bukkit.getMaxPlayers() } returns 20
        every { Bukkit.getTPS() } returns doubleArrayOf(20.0, 19.5, 19.0)
        every { Bukkit.getAverageTickTime() } returns 5.0
        every { Bukkit.getWorlds() } returns emptyList()
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(Bukkit::class)
    }

    @Test
    @DisplayName("bindTo registers player and server gauges")
    fun bindToRegistersPlayerAndServerGauges() {
        val registry = SimpleMeterRegistry()

        MinecraftMetrics().bindTo(registry)

        // 主要なゲージが登録されていることを確認
        assertNotNull(registry.find("minecraft.players.online").gauge())
        assertNotNull(registry.find("minecraft.players.max").gauge())
        assertNotNull(registry.find("minecraft.server.tick.duration.average").gauge())
        assertNotNull(registry.find("minecraft.worlds.count").gauge())

        // TPSはwindowタグ付きで3つ登録される
        assertEquals(3, registry.find("minecraft.server.tps").gauges().size)
    }

    @Test
    @DisplayName("Gauges reflect server state")
    fun gaugesReflectServerState() {
        val registry = SimpleMeterRegistry()
        MinecraftMetrics().bindTo(registry)

        // モックしたサーバー状態がゲージ値に反映されることを確認
        assertEquals(0.0, registry.find("minecraft.players.online").gauge()!!.value())
        assertEquals(20.0, registry.find("minecraft.players.max").gauge()!!.value())
        assertEquals(20.0, registry.find("minecraft.server.tps").tag("window", "1m").gauge()!!.value())
        assertEquals(19.0, registry.find("minecraft.server.tps").tag("window", "15m").gauge()!!.value())
        assertEquals(5.0, registry.find("minecraft.server.tick.duration.average").gauge()!!.value())
    }
}
