package party.morino.mineauth.addons.vault.config

import kotlinx.serialization.Serializable

/**
 * Vaultアドオンの設定
 * plugins/MineAuth-addon-vault/config.json に保存される
 */
@Serializable
data class VaultConfig(
    // 送金額の上限
    val maxTransferAmount: Double = 1_000_000_000.0,
) {
    init {
        require(maxTransferAmount > 0) { "maxTransferAmount must be greater than 0" }
    }
}
