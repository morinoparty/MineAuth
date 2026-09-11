package party.morino.mineauth.addons.griefprevention.utils

import kotlin.math.max
import kotlin.math.sqrt

/**
 * 座標とクレーム（軸に平行な直方体）との距離を計算する純粋関数群
 * Bukkitに依存しないため、単体テストで検証できる
 */
object ClaimDistance {

    /**
     * 点から直方体（両端を含むブロック範囲）までのユークリッド距離を求める
     *
     * 各軸について「点が範囲の外側にどれだけはみ出しているか」を求め、その合成が距離になる。
     * 点が範囲内にある軸は寄与が0になるため、点が直方体の内部にあれば距離は0となる。
     *
     * @param x 点のX座標
     * @param y 点のY座標
     * @param z 点のZ座標
     * @param minX 直方体の最小X（含む）
     * @param minY 直方体の最小Y（含む）
     * @param minZ 直方体の最小Z（含む）
     * @param maxX 直方体の最大X（含む）
     * @param maxY 直方体の最大Y（含む）
     * @param maxZ 直方体の最大Z（含む）
     * @return 点から直方体表面までの距離（内部なら0）
     */
    fun distanceToBox(
        x: Double, y: Double, z: Double,
        minX: Int, minY: Int, minZ: Int,
        maxX: Int, maxY: Int, maxZ: Int,
    ): Double {
        val dx = axisGap(x, minX, maxX)
        val dy = axisGap(y, minY, maxY)
        val dz = axisGap(z, minZ, maxZ)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 1軸上で、点が[min, max]の範囲からどれだけ離れているかを返す
     * 範囲内なら0、範囲外ならその軸方向の距離
     */
    private fun axisGap(value: Double, min: Int, max: Int): Double =
        max(0.0, max(min - value, value - max))
}
