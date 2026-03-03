package party.morino.mineauth.addons.griefprevention.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ryanhamshire.GriefPrevention.Claim
import me.ryanhamshire.GriefPrevention.DataStore
import me.ryanhamshire.GriefPrevention.GriefPrevention
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.griefprevention.config.GriefPreventionConfig
import party.morino.mineauth.addons.griefprevention.data.*
import party.morino.mineauth.addons.griefprevention.utils.coroutines.minecraft
import party.morino.mineauth.api.annotations.AuthedAccessUser
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.PostMapping
import party.morino.mineauth.api.annotations.RequestBody
import party.morino.mineauth.api.annotations.TargetPlayer
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus

/**
 * GriefPreventionのクレーム操作を行うハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class ClaimHandler : KoinComponent {
    private val dataStore: DataStore by inject()
    private val economy: Economy by inject()
    private val config: GriefPreventionConfig by inject()

    /**
     * プレイヤーのクレーム一覧を取得する
     * GET /claims/{player}
     *
     * @param player 対象プレイヤー（me/UUID/名前で指定）
     * @return クレーム情報のサマリー
     */
    @GetMapping("/claims/{player}")
    suspend fun getMyClaims(@TargetPlayer player: OfflinePlayer): ClaimSummaryResponse {
        return withContext(Dispatchers.minecraft) {
            // プレイヤーデータをGriefPreventionから取得
            val playerData = dataStore.getPlayerData(player.uniqueId)
            // クレーム一覧をデータクラスに変換
            val claims = playerData.claims.map { it.toClaimData() }

            ClaimSummaryResponse(
                claims = claims,
                totalClaimCount = claims.size,
                accruedClaimBlocks = playerData.accruedClaimBlocks,
                bonusClaimBlocks = playerData.bonusClaimBlocks,
                remainingClaimBlocks = playerData.getRemainingClaimBlocks(),
            )
        }
    }

    /**
     * クレームブロックを購入する
     * POST /claims/purchase
     *
     * Vault Economyと連携し、プレイヤーの所持金からクレームブロックを購入する。
     * GriefPreventionのconfig_economy_claimBlocksPurchaseCostで設定された単価を使用する。
     *
     * @param player 認証済みプレイヤー
     * @param request 購入リクエスト
     * @return 購入結果
     */
    @PostMapping("/claims/purchase")
    suspend fun purchaseClaimBlocks(
        @AuthedAccessUser player: OfflinePlayer,
        @RequestBody request: PurchaseRequest,
    ): PurchaseResponse {
        // 購入数のバリデーション
        if (request.blockCount <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Block count must be greater than 0")
        }
        if (request.blockCount > config.maxPurchaseBlocks) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Block count exceeds maximum limit of ${config.maxPurchaseBlocks}")
        }

        return withContext(Dispatchers.minecraft) {
            // GriefPreventionのブロック単価を取得
            val costPerBlock = GriefPrevention.instance.config_economy_claimBlocksPurchaseCost
            if (costPerBlock <= 0) {
                throw HttpError(HttpStatus.BAD_REQUEST, "Claim block purchasing is disabled on this server")
            }

            // 合計コストを計算
            val totalCost = costPerBlock * request.blockCount

            // 所持金を確認
            val balance = economy.getBalance(player)
            if (balance < totalCost) {
                throw HttpError(
                    HttpStatus.BAD_REQUEST,
                    "Insufficient funds. Required: $totalCost, Available: $balance",
                )
            }

            // 引き落とし処理
            val withdrawResult = economy.withdrawPlayer(player, totalCost)
            if (!withdrawResult.transactionSuccess()) {
                throw HttpError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to withdraw funds: ${withdrawResult.errorMessage}",
                )
            }

            // ボーナスクレームブロックに加算（失敗時は返金で補償）
            val playerData = try {
                val data = dataStore.getPlayerData(player.uniqueId)
                data.setBonusClaimBlocks(
                    data.bonusClaimBlocks + request.blockCount,
                )
                dataStore.savePlayerData(player.uniqueId, data)
                data
            } catch (e: Exception) {
                // データ保存に失敗した場合、引き落とした金額を返金する
                economy.depositPlayer(player, totalCost)
                throw HttpError(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to save claim block data. Funds have been refunded.",
                )
            }

            PurchaseResponse(
                purchased = request.blockCount,
                totalCost = totalCost,
                newBalance = economy.getBalance(player),
                remainingClaimBlocks = playerData.getRemainingClaimBlocks(),
            )
        }
    }

    // ========================================
    // ヘルパーメソッド
    // ========================================

    /**
     * GriefPreventionのClaimをClaimDataに変換する
     */
    private fun Claim.toClaimData(): ClaimData {
        val lesser = this.lesserBoundaryCorner
        val greater = this.greaterBoundaryCorner
        val worldName = lesser.world?.name ?: "unknown"

        return ClaimData(
            claimId = this.id,
            owner = this.ownerID,
            world = worldName,
            lesserCorner = ClaimCornerData(
                world = worldName,
                x = lesser.blockX,
                y = lesser.blockY,
                z = lesser.blockZ,
            ),
            greaterCorner = ClaimCornerData(
                world = greater.world?.name ?: worldName,
                x = greater.blockX,
                y = greater.blockY,
                z = greater.blockZ,
            ),
            area = this.area.toLong(),
        )
    }
}
