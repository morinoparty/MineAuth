package party.morino.mineauth.addons.griefprevention.utils

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * ClaimDistance.distanceToBoxのテスト
 * 直方体は (0,0,0)-(10,10,10) を共通で使う
 */
class ClaimDistanceTest {

    private fun distance(x: Double, y: Double, z: Double) =
        ClaimDistance.distanceToBox(x, y, z, 0, 0, 0, 10, 10, 10)

    @Test
    @DisplayName("Returns 0 when the point is inside the box")
    fun returnsZeroInsideBox() {
        assertEquals(0.0, distance(5.0, 5.0, 5.0))
    }

    @Test
    @DisplayName("Returns axis distance when only one axis is outside")
    fun returnsAxisDistanceWhenOneAxisOutside() {
        assertEquals(5.0, distance(15.0, 5.0, 5.0))
    }

    @Test
    @DisplayName("Returns euclidean distance to the nearest corner")
    fun returnsEuclideanDistanceToCorner() {
        // (13, 5, 14) → 最も近い角 (10, 5, 10) との距離は sqrt(3^2 + 4^2) = 5
        assertEquals(5.0, distance(13.0, 5.0, 14.0))
    }
}
