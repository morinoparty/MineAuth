package party.morino.mineauth.addons.quickshop.config

import kotlinx.serialization.Serializable

/**
 * QuickShop-Hikariアドオンの設定
 * plugins/MineAuth-addon-quickshop-hikari/config.json に保存される
 */
@Serializable
data class QuickShopConfig(
    // ページネーションのデフォルト取得件数
    val defaultLimit: Int = 200,
    // ページネーションの最大取得件数
    val maxLimit: Int = 1000,
) {
    init {
        require(maxLimit > 0) { "maxLimit must be greater than 0" }
        require(defaultLimit > 0) { "defaultLimit must be greater than 0" }
        require(defaultLimit <= maxLimit) { "defaultLimit must be less than or equal to maxLimit" }
    }
}
