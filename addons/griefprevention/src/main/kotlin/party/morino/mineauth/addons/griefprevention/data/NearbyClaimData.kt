package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable

/**
 * 周辺検索で見つかったクレームと、検索座標からの距離
 */
@Serializable
data class NearbyClaimData(
    // クレーム情報
    val claim: ClaimData,
    // 検索座標からクレーム境界までのユークリッド距離（ブロック）。座標がクレーム内部なら0
    val distance: Double,
)
