package party.morino.mineauth.api.model.bukkit

import io.papermc.paper.registry.RegistryAccess
import io.papermc.paper.registry.RegistryKey
import kotlinx.serialization.Serializable
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.inventory.meta.ItemMeta
import party.morino.mineauth.api.model.kyori.ComponentData

@Serializable
data class ItemMetaData(
    val displayName: ComponentData,
    val enchantment: Map<String, Int>,
    val customModelData: Int? = null
) {

    fun toItemMeta(material: Material): ItemMeta {
        val itemMeta = Bukkit.getItemFactory().getItemMeta(material)
        itemMeta?.let {
            it.displayName(displayName.toComponent())
            enchantment.forEach { (key, value) ->
                val registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.ENCHANTMENT)
                it.addEnchant(registry.get(NamespacedKey.minecraft(key))!!, value, true)
            }
            customModelData?.let { it1 -> it.setCustomModelData(it1) }
        }
        return itemMeta!!
    }

    companion object {
        fun fromItemMeta(itemMeta: ItemMeta): ItemMetaData {
            return ItemMetaData(
                enchantment = itemMeta.enchants.map { it.key.key.key to it.value }.toMap(),
                displayName = ComponentData.Companion.fromComponent(itemMeta.displayName() ?: ComponentData("").toComponent()),
                customModelData = if (itemMeta.hasCustomModelData()) itemMeta.customModelData else null,
            )
        }
    }
}
