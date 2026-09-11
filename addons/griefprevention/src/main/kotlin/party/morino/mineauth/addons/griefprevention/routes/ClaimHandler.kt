package party.morino.mineauth.addons.griefprevention.routes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ryanhamshire.GriefPrevention.Claim
import me.ryanhamshire.GriefPrevention.DataStore
import me.ryanhamshire.GriefPrevention.util.BoundingBox
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.griefprevention.config.GriefPreventionConfig
import party.morino.mineauth.addons.griefprevention.data.*
import party.morino.mineauth.addons.griefprevention.utils.ClaimDistance
import party.morino.mineauth.addons.griefprevention.utils.coroutines.minecraft
import party.morino.mineauth.api.CallerType
import party.morino.mineauth.api.annotations.Authenticated
import party.morino.mineauth.api.annotations.Body
import party.morino.mineauth.api.annotations.Caller
import party.morino.mineauth.api.annotations.Get
import party.morino.mineauth.api.annotations.PlayerParam
import party.morino.mineauth.api.annotations.Post
import party.morino.mineauth.api.annotations.Query
import party.morino.mineauth.api.auth.Principal
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import kotlin.math.ceil
import kotlin.math.floor

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
    @Get("/claims/{player}")
    @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun getMyClaims(@PlayerParam("player") player: OfflinePlayer): ClaimSummaryResponse {
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
     * 座標の周辺にあるクレームを検索する
     * GET /claims/nearby?world=&x=&y=&z=&radius=
     *
     * 検索座標を中心とした半径radiusの球に触れるトップレベルのクレームを、距離の昇順で返す。
     * 距離は座標からクレーム境界（直方体）までの最短距離で、座標がクレーム内部なら0となる。
     * サブディビジョン（子クレーム）はGriefPreventionのチャンク索引に含まれないため対象外。
     *
     * @param worldName 検索するワールド名
     * @param x 中心X座標
     * @param y 中心Y座標
     * @param z 中心Z座標
     * @param radius 検索半径（ブロック）。1以上かつ設定のmaxNearbyRadius以下
     * @return 半径内のクレーム一覧
     */
    @Get("/claims/nearby")
    @Authenticated(callers = [CallerType.USER, CallerType.SERVICE])
    suspend fun getNearbyClaims(
        @Query("world") worldName: String,
        @Query("x") x: Double,
        @Query("y") y: Double,
        @Query("z") z: Double,
        @Query("radius") radius: Int,
    ): NearbyClaimsResponse {
        // 半径のバリデーション（大きすぎる半径は走査チャンク数が二乗で増えるため上限を設ける）
        if (radius <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Radius must be greater than 0")
        }
        if (radius > config.maxNearbyRadius) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Radius exceeds maximum limit of ${config.maxNearbyRadius}")
        }

        return withContext(Dispatchers.minecraft) {
            val world = Bukkit.getWorld(worldName)
                ?: throw HttpError(HttpStatus.NOT_FOUND, "World not found: $worldName")

            // 半径を包含する直方体に触れるチャンクからクレーム候補を集める（粗い絞り込み）
            // toInt()は0方向へ切り捨てるため負の座標で範囲が縮む。外側へ丸めて取りこぼしを防ぐ
            val searchBox = BoundingBox(
                floor(x - radius).toInt(), floor(y - radius).toInt(), floor(z - radius).toInt(),
                ceil(x + radius).toInt(), ceil(y + radius).toInt(), ceil(z + radius).toInt(),
            )
            val candidates = dataStore.getChunkClaims(world, searchBox)

            // 実際の距離で絞り込み、近い順に並べる（精密な絞り込み）
            val claims = candidates
                .map { claim -> NearbyClaimData(claim = claim.toClaimData(), distance = claim.distanceFrom(x, y, z)) }
                .filter { it.distance <= radius }
                .sortedBy { it.distance }

            NearbyClaimsResponse(
                world = world.name,
                x = x,
                y = y,
                z = z,
                radius = radius,
                claims = claims,
            )
        }
    }

    /**
     * クレームブロックを購入する
     * POST /claims/purchase
     *
     * Vault Economyと連携し、プレイヤーの所持金からクレームブロックを購入する。
     * アドオン設定（GriefPreventionConfig.claimBlockCost）で設定された単価を使用する。
     * GriefPrevention 18.0.0 で本体の経済機能が削除されたため、単価はアドオン側で管理する。
     *
     * @param caller 認証済みプレイヤー
     * @param request 購入リクエスト
     * @return 購入結果
     */
    @Post("/claims/purchase")
    @Authenticated
    suspend fun purchaseClaimBlocks(
        @Caller caller: Principal.User,
        @Body request: PurchaseRequest,
    ): PurchaseResponse {
        val player = caller.offlinePlayer

        // 購入数のバリデーション
        if (request.blockCount <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Block count must be greater than 0")
        }
        if (request.blockCount > config.maxPurchaseBlocks) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Block count exceeds maximum limit of ${config.maxPurchaseBlocks}")
        }

        return withContext(Dispatchers.minecraft) {
            // アドオン設定からクレームブロックの単価を取得
            val costPerBlock = config.claimBlockCost
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
     * 座標からこのクレームの境界までの最短距離を求める
     */
    private fun Claim.distanceFrom(x: Double, y: Double, z: Double): Double {
        val lesser = this.lesserBoundaryCorner
        val greater = this.greaterBoundaryCorner
        return ClaimDistance.distanceToBox(
            x, y, z,
            lesser.blockX, lesser.blockY, lesser.blockZ,
            greater.blockX, greater.blockY, greater.blockZ,
        )
    }

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
