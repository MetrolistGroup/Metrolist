package ultra.hooks

/**
 * Immutable snapshot of every registered contributor. Built once by :ultra-wiring at startup and
 * swapped into [UltraHooks] atomically, so seams never observe a half-filled registry.
 */
class HookRegistry(
    apiClient: Collection<ApiClientContributor> = emptyList(),
    streamClient: Collection<StreamClientContributor> = emptyList(),
    audioProcessors: Collection<AudioProcessorsContributor> = emptyList(),
    streamResolvers: Collection<StreamResolverContributor> = emptyList(),
    destinations: Collection<DestinationContributor> = emptyList(),
    settingsEntries: Collection<SettingsEntryContributor> = emptyList(),
) {
    internal val apiClient: List<ApiClientContributor> = apiClient.toList()
    internal val streamClient: List<StreamClientContributor> = streamClient.toList()
    internal val audioProcessors: List<AudioProcessorsContributor> = audioProcessors.toList()
    internal val streamResolvers: List<StreamResolverContributor> = streamResolvers.toList()
    internal val destinations: List<DestinationContributor> = destinations.toList()
    internal val settingsEntries: List<SettingsEntryContributor> = settingsEntries.toList()

    internal companion object {
        /** Identity marker for "nothing installed yet"; UltraHooks.isInstalled compares against it. */
        val EMPTY = HookRegistry()
    }
}
