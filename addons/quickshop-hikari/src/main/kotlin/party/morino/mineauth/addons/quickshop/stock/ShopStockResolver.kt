package party.morino.mineauth.addons.quickshop.stock

import com.ghostchu.quickshop.api.shop.Shop
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.bukkit.Chunk
import org.bukkit.plugin.Plugin
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.quickshop.config.QuickShopConfig
import party.morino.mineauth.addons.quickshop.utils.coroutines.minecraft

/**
 * ショップの在庫（販売ショップ）・空き容量（買取ショップ）を数える
 *
 * QuickShop は読み込まれていないチャンクのショップの在庫を常に 0 と返すため、
 * そのチャンクを Paper の非同期チャンク読み込み（メインスレッドを止めない）で読み込み、
 * 数え終わるまでプラグインのチャンクチケットで読み込んだままにしてから数える。
 * 一覧 API で毎回大量のチャンクを読み込まないよう、結果は [ShopStockCache] に短時間保存し、
 * 1 リクエストで読み込むチャンク数は [QuickShopConfig.maxChunkLoadsPerRequest] までに制限する。
 *
 * メインスレッド以外（HTTP のワーカースレッド）から呼ぶこと。
 */
class ShopStockResolver : KoinComponent {
    private val plugin: Plugin by inject()
    private val config: QuickShopConfig by inject()
    private val cache: ShopStockCache by inject()

    /**
     * 数える対象のショップ
     *
     * @property shop 対象のショップ
     * @property chunk ショップのチャンク
     * @property owner 買取ショップのオーナー（所持金で制限するため）。販売ショップではnull
     */
    private data class Pending(val shop: Shop<*, *>, val chunk: ChunkKey, val owner: UUID?)

    /**
     * メインスレッドでの振り分け結果
     *
     * @property decided 数えずに決まった結果（アドミンショップ・ワールド未読み込み・読み込み上限超過）
     * @property toCount 数えるショップ
     * @property held チケットを付けた（付ける）チャンク。読み込み中のチャンクは読み込み完了時にチケットが付く
     */
    private data class Classified(
        val decided: Map<Long, ShopStock>,
        val toCount: List<Pending>,
        val held: List<CompletableFuture<Chunk?>>,
    )

    /**
     * ショップごとの在庫・空き容量を求める
     *
     * @param shops 対象のショップ
     * @return shopId ごとの結果（すべてのショップを含む）
     */
    suspend fun resolve(shops: List<Shop<*, *>>): Map<Long, ShopStock> {
        // 有効期限内の結果があるショップは数え直さない
        cache.purgeExpired()
        val cached = shops.mapNotNull { shop -> cache.get(shop.shopId)?.let { shop.shopId to it } }.toMap()
        val misses = shops.filter { it.shopId !in cached }
        if (misses.isEmpty()) return cached

        // ショップの位置とチャンクの状態はメインスレッドで調べ、チャンクにチケットを付ける（読み込みが必要なら非同期で始める）
        val classified = withContext(Dispatchers.minecraft) { classify(misses) }
        val counted = try {
            // チャンクの読み込みはメインスレッドの外で待つ。読み込めなかったチャンクのショップは Unknown になる
            classified.held.forEach { future ->
                try {
                    future.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    plugin.logger.warning("Failed to load a chunk to count shop stock: ${e.message}")
                }
            }

            // 買取ショップはオーナーの所持金でも制限する（オフラインのプレイヤーはディスクから読むことがあるので IO で取る）
            val balances = OwnerBalance.fetch(classified.toCount.mapNotNull { it.owner }.toSet())

            // インベントリはメインスレッドでしか触れない
            withContext(Dispatchers.minecraft) { classified.toCount.associate { it.shop.shopId to count(it, balances) } }
        } finally {
            // 例外・キャンセルでも必ずチケットを外し、チャンクが読み込まれたまま残らないようにする
            withContext(NonCancellable + Dispatchers.minecraft) { release(classified.held) }
        }

        // 数えられた結果だけをキャッシュに入れる（Unknown は入れない）
        (classified.decided + counted).forEach { (shopId, stock) -> cache.put(shopId, stock) }
        return cached + classified.decided + counted
    }

    /**
     * ショップを振り分け、数えるショップのチャンクにチケットを付ける（メインスレッドで呼ぶ）
     *
     * 読み込み済みのチャンクにあるショップは読み込み上限を消費しない。
     *
     * @param shops キャッシュに無いショップ
     * @return 振り分け結果
     */
    private fun classify(shops: List<Shop<*, *>>): Classified {
        val decided = mutableMapOf<Long, ShopStock>()
        val pending = mutableListOf<Pending>()
        for (shop in shops) {
            // 6.3.0 で Shop はジェネリクスになったため、Bukkit の Location は bukkitLocation() で取る
            val location = shop.bukkitLocation()
            when {
                // アドミンショップは在庫の制限が無い
                shop.isUnlimited -> decided[shop.shopId] = ShopStock.Unlimited
                // ワールドが読み込まれていなければチャンクも読み込めない
                !location.isWorldLoaded -> decided[shop.shopId] = ShopStock.Unknown
                else -> {
                    val chunk = ChunkKey(location.world.uid, location.blockX shr 4, location.blockZ shr 4)
                    val owner = if (shop.isBuying) shop.owner.uniqueId else null
                    pending += Pending(shop, chunk, owner)
                }
            }
        }

        // 読み込まれていないチャンクのうち、上限までを読み込む
        val unloaded = pending.filterNot { isLoaded(it.chunk) }.map { it.chunk }
        val toLoad = ChunkKey.selectToLoad(unloaded, config.maxChunkLoadsPerRequest)

        // 読み込み済みでも読み込み対象でもないチャンクのショップは、今回は数えない
        val (toCount, skipped) = pending.partition { isLoaded(it.chunk) || it.chunk in toLoad }
        skipped.forEach { decided[it.shop.shopId] = ShopStock.Unknown }

        // 数えるショップのチャンクにチケットを付け、数え終わるまで解放されないようにする
        val held = toCount.map { it.chunk }.distinct().map { key -> hold(key) }
        return Classified(decided, toCount, held)
    }

    /**
     * チャンクにプラグインのチャンクチケットを付ける（メインスレッドで呼ぶ）
     *
     * 読み込まれていないチャンクは非同期で読み込み、読み込み完了時（メインスレッド）にチケットを付ける。
     * 読み込みとチケットの付与の間に解放されないよう、完了を待つ側ではなく完了時に付ける。
     *
     * @param key 対象のチャンク
     * @return チケットを付けたチャンク。読み込めなかった場合はnull
     */
    private fun hold(key: ChunkKey): CompletableFuture<Chunk?> {
        val world = plugin.server.getWorld(key.worldId) ?: return CompletableFuture.completedFuture(null)
        if (world.isChunkLoaded(key.x, key.z)) {
            val chunk = world.getChunkAt(key.x, key.z)
            chunk.addPluginChunkTicket(plugin)
            return CompletableFuture.completedFuture(chunk)
        }
        // 既存のチャンクだけを読み込み、まだ生成されていない場所に新しいチャンクを作らない
        return world.getChunkAtAsync(key.x, key.z, false).thenApply { chunk: Chunk? ->
            chunk?.addPluginChunkTicket(plugin)
            chunk
        }
    }

    /**
     * [hold] で付けたチケットを外す（メインスレッドで呼ぶ）
     *
     * まだ読み込み中のチャンクは、読み込み完了時に付くチケットをその場で外す。
     *
     * @param held [hold] の結果
     */
    private fun release(held: List<CompletableFuture<Chunk?>>) {
        held.forEach { future ->
            future.thenAccept { chunk: Chunk? -> chunk?.removePluginChunkTicket(plugin) }
        }
    }

    /**
     * 1 つのショップを数える（メインスレッドで呼ぶ）
     *
     * @param pending 対象のショップ
     * @param balances オーナーごとの所持金
     * @return 数えた結果。チャンクが読み込まれていなければ [ShopStock.Unknown]
     */
    private fun count(pending: Pending, balances: Map<UUID, Double>): ShopStock {
        val shop = pending.shop
        // 読み込みに失敗したチャンクは数えられない
        if (!isLoaded(pending.chunk)) return ShopStock.Unknown
        // 1 つのショップの失敗で一覧全体を失敗させない
        return runCatching {
            if (shop.isBuying) {
                ShopStock.buyable(shop.remainingSpace, shop.price, pending.owner?.let { balances[it] })
            } else {
                ShopStock.Counted(shop.remainingStock)
            }
        }.getOrElse { e ->
            plugin.logger.warning("Failed to count the stock of shop ${shop.shopId}: ${e.message}")
            ShopStock.Unknown
        }
    }

    /**
     * チャンクが読み込まれているか（メインスレッドで呼ぶ）
     */
    private fun isLoaded(chunk: ChunkKey): Boolean =
        plugin.server.getWorld(chunk.worldId)?.isChunkLoaded(chunk.x, chunk.z) == true
}
