package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import java.util.*

/**
 * 個別のクレーム（土地保護）情報
 */
@Serializable
data class ClaimData(
    // クレームの一意識別子
    val claimId: Long,
    // クレーム所有者のUUID
    val owner: @Serializable(with = UUIDSerializer::class) UUID?,
    // クレームが存在するワールド名
    val world: String,
    // クレームの小さい方の角座標（南西下）
    val lesserCorner: ClaimCornerData,
    // クレームの大きい方の角座標（北東上）
    val greaterCorner: ClaimCornerData,
    // クレームの面積（ブロック数）
    val area: Long,
)
