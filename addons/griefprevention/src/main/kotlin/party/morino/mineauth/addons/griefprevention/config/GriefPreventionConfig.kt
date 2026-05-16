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
    // クレームブロック1個あたりの購入単価。
    // GriefPrevention 18.0.0 で経済機能（config_economy_claimBlocksPurchaseCost）が
    // 本体から削除されたため、アドオン側で単価を保持する。
    // 0 以下の場合はクレームブロック購入を無効として扱う。
    val claimBlockCost: Double = 0.0,
) {
    init {
        require(maxPurchaseBlocks > 0) { "maxPurchaseBlocks must be greater than 0" }
        require(claimBlockCost >= 0) { "claimBlockCost must be greater than or equal to 0" }
    }
}
