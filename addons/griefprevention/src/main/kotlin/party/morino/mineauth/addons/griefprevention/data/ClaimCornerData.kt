package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable

/**
 * クレームの角座標データ
 * lesserBoundaryCorner / greaterBoundaryCorner に対応する
 */
@Serializable
data class ClaimCornerData(
    // ワールド名（例: world, world_nether, world_the_end）
    val world: String,
    // X座標
    val x: Int,
    // Y座標
    val y: Int,
    // Z座標
    val z: Int,
)
