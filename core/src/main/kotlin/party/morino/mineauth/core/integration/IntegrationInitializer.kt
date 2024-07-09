package party.morino.mineauth.core.integration

import party.morino.mineauth.core.integration.quickshop_hikari.QuickshopIntegration
import party.morino.mineauth.core.integration.vault.VaultIntegration

object IntegrationInitializer {
    private val integrations = mutableListOf<Integration>()
    val availableIntegrations: List<Integration>
        get() = integrations.filter { it.available }

    fun initialize() {
        integrations.add(VaultIntegration)
        integrations.add(QuickshopIntegration)

        integrations.forEach {
            it.initialize()
        }
    }
}