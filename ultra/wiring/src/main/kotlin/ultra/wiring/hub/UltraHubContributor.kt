package ultra.wiring.hub

import ultra.hooks.DestinationContributor
import ultra.hooks.SettingsEntryContributor
import ultra.hooks.UltraDestination
import ultra.hooks.UltraSettingsEntry
import ultra.wiring.R
import javax.inject.Inject

/**
 * The built-in "Ultra" hub: one Settings entry (S9) that opens one screen (S8). It is the entry
 * point under which feature modules will later register their own screens, and it doubles as a live
 * diagnostic of which seams have contributors.
 */
class UltraHubContributor @Inject constructor() : DestinationContributor, SettingsEntryContributor {
    override fun destinations(): List<UltraDestination> =
        listOf(UltraDestination(ROUTE, R.string.ultra_title) { UltraHubScreen() })

    override fun entries(): List<UltraSettingsEntry> =
        listOf(UltraSettingsEntry(ROUTE, R.string.ultra_title, R.drawable.ultra_ic_bolt))

    companion object {
        const val ROUTE = "ultra"
    }
}
