package party.morino.mineauth.api.model.bukkit

import kotlinx.serialization.Serializable
import org.bukkit.Material

@Serializable
data class MaterialData(
    val name: String,
    val type: String
){
    fun toMaterial() = Material.getMaterial(name)!!
    companion object {
        fun fromMaterial(material: Material) = MaterialData(material.name, if(material.isBlock) "block" else "item")
    }
}
