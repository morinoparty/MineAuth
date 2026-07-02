package party.morino.mineauth.core.web.telemetry

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.binder.MeterBinder
import org.bukkit.Bukkit

/**
 * Minecraftサーバーのメトリクスを提供するMeterBinder
 *
 * 全てpull型のゲージとして登録するため、Bukkitスケジューラのタスクは不要。
 * ゲージの値はスクレイプ/エクスポート時（exporterスレッド）に評価されるので、
 * メインスレッド外から安全に読めるPaperのカウンタAPIのみを使用する。
 * （getLoadedChunks()やgetEntities()のようなコレクションのイテレートは禁止）
 *
 * ワールド別のゲージはbind時点に存在するワールドに対してのみ登録される。
 * bind後にロードされたワールドは対象外（v1の制約）。
 */
class MinecraftMetrics : MeterBinder {
    override fun bindTo(registry: MeterRegistry) {
        // オンラインプレイヤー数
        Gauge.builder("minecraft.players.online") { Bukkit.getOnlinePlayers().size }
            .description("Number of online players")
            .baseUnit("{player}")
            .register(registry)

        // 最大プレイヤー数
        Gauge.builder("minecraft.players.max") { Bukkit.getMaxPlayers() }
            .description("Maximum number of players")
            .baseUnit("{player}")
            .register(registry)

        // TPS（1分・5分・15分の移動平均をwindowタグで区別する）
        listOf("1m" to 0, "5m" to 1, "15m" to 2).forEach { (window, index) ->
            Gauge.builder("minecraft.server.tps") { Bukkit.getTPS().getOrElse(index) { 0.0 } }
                .description("Server ticks per second")
                .tag("window", window)
                .register(registry)
        }

        // 平均tick処理時間（MSPT）
        Gauge.builder("minecraft.server.tick.duration.average") { Bukkit.getAverageTickTime() }
            .description("Average tick duration (MSPT)")
            .baseUnit("ms")
            .register(registry)

        // ロード済みワールド数
        Gauge.builder("minecraft.worlds.count") { Bukkit.getWorlds().size }
            .description("Number of loaded worlds")
            .baseUnit("{world}")
            .register(registry)

        // ワールド別のエンティティ数・ロード済みチャンク数
        // Paperのカウンタアクセサ（単なるフィールド読み取り）のみを使うこと
        Bukkit.getWorlds().forEach { world ->
            val worldName = world.name

            Gauge.builder("minecraft.entities.count") {
                Bukkit.getWorld(worldName)?.entityCount ?: 0
            }
                .description("Number of entities in the world")
                .baseUnit("{entity}")
                .tag("world", worldName)
                .register(registry)

            Gauge.builder("minecraft.chunks.loaded") {
                Bukkit.getWorld(worldName)?.chunkCount ?: 0
            }
                .description("Number of loaded chunks in the world")
                .baseUnit("{chunk}")
                .tag("world", worldName)
                .register(registry)
        }
    }
}
