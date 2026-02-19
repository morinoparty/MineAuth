package party.morino.mineauth.addons.griefprevention.config

import kotlinx.serialization.Serializable

/**
 * GriefPreventionアドオンの設定
 * plugins/MineAuth-addon-griefprevention/config.json に保存される
 */
@Serializable
data class GriefPreventionConfig(
    // 一度に購入できるクレームブロックの上限
    val maxPurchaseBlocks: Int = 100_000,
) {
    init {
        require(maxPurchaseBlocks > 0) { "maxPurchaseBlocks must be greater than 0" }
    }
}
