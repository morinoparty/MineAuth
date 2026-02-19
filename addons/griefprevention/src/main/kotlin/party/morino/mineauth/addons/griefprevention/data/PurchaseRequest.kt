package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable

/**
 * クレームブロック購入リクエスト
 */
@Serializable
data class PurchaseRequest(
    // 購入するクレームブロック数
    val blockCount: Int,
)
