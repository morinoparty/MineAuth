package party.morino.mineauth.core.integration

import party.morino.mineauth.core.integration.luckperms.LuckPermsIntegration
import party.morino.mineauth.core.integration.vault.VaultIntegration

object IntegrationInitializer {
    private val integrations = mutableListOf<Integration>()
    val availableIntegrations: List<Integration>
        get() = integrations.filter { it.available }

    fun initialize() {
        integrations.add(VaultIntegration)
        integrations.add(LuckPermsIntegration)

        integrations.forEach {
            it.initialize()
        }
    }
}