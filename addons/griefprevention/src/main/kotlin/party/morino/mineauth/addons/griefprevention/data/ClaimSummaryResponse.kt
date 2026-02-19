package party.morino.mineauth.addons.griefprevention.data

import kotlinx.serialization.Serializable

/**
 * クレーム一覧のレスポンス
 * GET /claims/me で返却される
 */
@Serializable
data class ClaimSummaryResponse(
    // プレイヤーが所有するクレームのリスト
    val claims: List<ClaimData>,
    // 所有クレーム数
    val totalClaimCount: Int,
    // 蓄積されたクレームブロック数
    val accruedClaimBlocks: Int,
    // ボーナスクレームブロック数
    val bonusClaimBlocks: Int,
    // 残りの使用可能クレームブロック数
    val remainingClaimBlocks: Int,
)
