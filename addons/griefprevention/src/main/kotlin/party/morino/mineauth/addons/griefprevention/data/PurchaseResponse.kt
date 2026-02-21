package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable

/**
 * クレームブロック購入レスポンス
 */
@Serializable
data class PurchaseResponse(
    // 購入したクレームブロック数
    val purchased: Int,
    // 合計コスト
    val totalCost: Double,
    // 購入後の残高
    val newBalance: Double,
    // 購入後の残りクレームブロック数
    val remainingClaimBlocks: Int,
)
