package party.morino.mineauth.addons.quickshop.config

import kotlinx.serialization.Serializable

/**
 * QuickShop-Hikariアドオンの設定
 * plugins/MineAuth-addon-quickshop-hikari/config.json に保存される
 * 項目が無い古い config.json はデフォルト値で補われる
 */
@Serializable
data class QuickShopConfig(
    // ページネーションのデフォルト取得件数
    val defaultLimit: Int = 200,
    // ページネーションの最大取得件数
    val maxLimit: Int = 1000,
    // 在庫・空き容量の結果を使い回す秒数（0 ならキャッシュしない）
    val stockCacheTtlSeconds: Long = 120,
    // 在庫を数えるために 1 リクエストで読み込むチャンクの最大数（超えたショップの在庫は不明として返す）
    val maxChunkLoadsPerRequest: Int = 32,
) {
    init {
        require(maxLimit > 0) { "maxLimit must be greater than 0" }
        require(defaultLimit > 0) { "defaultLimit must be greater than 0" }
        require(defaultLimit <= maxLimit) { "defaultLimit must be less than or equal to maxLimit" }
        require(stockCacheTtlSeconds >= 0) { "stockCacheTtlSeconds must be greater than or equal to 0" }
        require(maxChunkLoadsPerRequest >= 0) { "maxChunkLoadsPerRequest must be greater than or equal to 0" }
    }
}
