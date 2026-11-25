package party.morino.mineauth.addons.quickshop.data

import kotlinx.serialization.Serializable
import party.morino.mineauth.api.utils.UUIDSerializer
import party.morino.mineauth.api.model.bukkit.ItemStackData
import party.morino.mineauth.api.model.bukkit.LocationData
import java.util.*

/**
 * ショップの情報
 *
 * @property shopId ショップID
 * @property owner オーナーのUUID。アドミンショップ（unlimited）ではnull
 * @property mode 販売（SELL）か買取（BUY）か
 * @property stackingAmount 1 取引あたりのアイテム数
 * @property remaining 販売ショップは在庫、買取ショップは買い取れる数（空き容量とオーナーの所持金の少ない方）。
 *   アドミンショップは -1。チャンクを読み込めなかったなどで数えられなかった場合はnull（JSON ではキーごと省略される）
 * @property unlimited アドミンショップなど、在庫・容量の制限が無いショップか
 * @property location ショップの位置
 * @property price 1 取引あたりの価格
 * @property item 取引するアイテム
 */
@Serializable
data class ShopData(
    val shopId: Long,
    val owner: @Serializable(with = UUIDSerializer::class) UUID?,
    val mode : ShopMode,
    val stackingAmount : Int,
    val remaining : Int?,
    val unlimited: Boolean,
    val location : LocationData,
    val price: Double,
    val item: ItemStackData,
)
