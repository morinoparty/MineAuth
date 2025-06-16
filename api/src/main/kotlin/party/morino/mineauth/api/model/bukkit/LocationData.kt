package party.morino.mineauth.api.model.bukkit

import kotlinx.serialization.Serializable
import org.bukkit.Bukkit
import org.bukkit.Location

@Serializable
data class LocationData(
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
){
    fun toLocation() = Location(Bukkit.getWorld(world), x, y, z, yaw, pitch)
    companion object {
        fun fromLocation(location: Location) = LocationData(location.world.name, location.x, location.y, location.z, location.yaw, location.pitch)
    }
}