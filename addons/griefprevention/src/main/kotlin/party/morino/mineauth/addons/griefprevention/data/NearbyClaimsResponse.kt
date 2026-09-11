package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable

/**
 * 周辺クレーム検索のレスポンス
 * GET /claims/nearby で返却される
 */
@Serializable
data class NearbyClaimsResponse(
    // 検索したワールド名
    val world: String,
    // 検索の中心X座標
    val x: Double,
    // 検索の中心Y座標
    val y: Double,
    // 検索の中心Z座標
    val z: Double,
    // 検索半径（ブロック）
    val radius: Int,
    // 半径内に存在するクレーム（距離の昇順）
    val claims: List<NearbyClaimData>,
)
