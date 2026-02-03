package party.morino.mineauth.addons.vault.routes

import kotlinx.serialization.Serializable
import net.milkbowl.vault.economy.Economy
import org.bukkit.OfflinePlayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import party.morino.mineauth.addons.vault.data.RemittanceData
import party.morino.mineauth.api.annotations.AuthedAccessUser
import party.morino.mineauth.api.annotations.GetMapping
import party.morino.mineauth.api.annotations.PostMapping
import party.morino.mineauth.api.annotations.RequestBody
import party.morino.mineauth.api.http.HttpError
import party.morino.mineauth.api.http.HttpStatus
import java.util.logging.Logger

/**
 * 残高取得のレスポンス
 */
@Serializable
data class BalanceResponse(
    val balance: Double
)

/**
 * 送金結果のレスポンス
 */
@Serializable
data class TransferResponse(
    val message: String,
    val balance: Double,
    val recipient: String,
    val amount: Double
)

/**
 * Vaultの経済機能を提供するハンドラー
 * /api/v1/plugins/{plugin-name}/ 配下にエンドポイントを提供する
 */
class VaultHandler : KoinComponent {
    private val economy: Economy by inject()

    companion object {
        // 送金額の上限（サーバー設定で変更可能にする場合は設定ファイルから読み込む）
        private const val MAX_TRANSFER_AMOUNT = 1_000_000_000.0
        private val logger: Logger = Logger.getLogger(VaultHandler::class.java.name)
    }

    /**
     * 自分の残高を取得する
     * GET /balance/me
     *
     * @param player 認証済みプレイヤー
     * @return 残高を含むレスポンス
     */
    @GetMapping("/balance/me")
    suspend fun getMyBalance(@AuthedAccessUser player: OfflinePlayer): BalanceResponse {
        val balance = economy.getBalance(player)
        return BalanceResponse(balance = balance)
    }

    /**
     * 他のプレイヤーに送金する
     * POST /send
     *
     * @param player 認証済みプレイヤー（送金元）
     * @param data 送金データ（送金先と金額）
     * @return 送金結果のレスポンス
     */
    @PostMapping("/send")
    suspend fun sendMoney(
        @AuthedAccessUser player: OfflinePlayer,
        @RequestBody data: RemittanceData
    ): TransferResponse {
        val target = data.target
        val amount = data.amount

        // セキュリティ: 入力検証を強化
        validateTransferRequest(player, target, amount)

        // 残高チェック（エラーメッセージに残高を含めない）
        val senderBalance = economy.getBalance(player)
        if (senderBalance < amount) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Insufficient balance")
        }

        // 送金処理を実行
        executeTransfer(player, target, amount)

        val newBalance = economy.getBalance(player)
        val targetName = target.name ?: target.uniqueId.toString()

        return TransferResponse(
            message = "Successfully sent $amount to $targetName",
            balance = newBalance,
            recipient = targetName,
            amount = amount
        )
    }

    /**
     * 送金リクエストのバリデーション
     * セキュリティ上の検証を一箇所にまとめる
     */
    private fun validateTransferRequest(sender: OfflinePlayer, target: OfflinePlayer, amount: Double) {
        // 金額が有限値であることを確認（NaN/Infinityを拒否）
        if (!amount.isFinite()) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Invalid amount: must be a finite number")
        }

        // 金額が正の値であることを確認
        if (amount <= 0) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Amount must be greater than 0")
        }

        // 金額の上限チェック
        if (amount > MAX_TRANSFER_AMOUNT) {
            throw HttpError(
                HttpStatus.BAD_REQUEST,
                "Amount exceeds maximum transfer limit"
            )
        }

        // 自分自身への送金を禁止
        if (sender.uniqueId == target.uniqueId) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Cannot send money to yourself")
        }

        // 送金元の口座存在チェック
        if (!economy.hasAccount(sender)) {
            throw HttpError(HttpStatus.BAD_REQUEST, "Sender account not found")
        }

        // 送金先の口座存在チェック
        if (!economy.hasAccount(target)) {
            throw HttpError(HttpStatus.NOT_FOUND, "Target account not found")
        }
    }

    /**
     * 送金処理を実行する
     * 出金→入金の順で実行し、入金失敗時は返金を試みる
     */
    private fun executeTransfer(sender: OfflinePlayer, target: OfflinePlayer, amount: Double) {
        // 出金処理
        val withdrawResult = economy.withdrawPlayer(sender, amount)
        if (!withdrawResult.transactionSuccess()) {
            logger.warning(
                "Withdraw failed for ${sender.uniqueId}: ${withdrawResult.errorMessage}"
            )
            throw HttpError(HttpStatus.INTERNAL_SERVER_ERROR, "Transaction failed")
        }

        // 入金処理
        val depositResult = economy.depositPlayer(target, amount)
        if (!depositResult.transactionSuccess()) {
            // 入金失敗時は返金を試みる
            logger.severe(
                "Deposit failed for ${target.uniqueId} after successful withdraw from ${sender.uniqueId}. " +
                    "Amount: $amount. Error: ${depositResult.errorMessage}"
            )

            val refundResult = economy.depositPlayer(sender, amount)
            if (!refundResult.transactionSuccess()) {
                // 返金も失敗した場合は重大エラーとしてログに記録
                logger.severe(
                    "CRITICAL: Refund failed for ${sender.uniqueId}. " +
                        "Amount: $amount. Manual intervention required!"
                )
            } else {
                logger.info("Refund successful for ${sender.uniqueId}. Amount: $amount")
            }

            throw HttpError(HttpStatus.INTERNAL_SERVER_ERROR, "Transaction failed")
        }

        logger.info(
            "Transfer successful: ${sender.uniqueId} -> ${target.uniqueId}, Amount: $amount"
        )
    }
}
