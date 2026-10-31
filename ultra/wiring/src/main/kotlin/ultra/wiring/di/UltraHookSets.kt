package ultra.wiring.di

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ultra.hooks.ApiClientContributor
import ultra.hooks.AudioProcessorsContributor
import ultra.hooks.DestinationContributor
import ultra.hooks.SettingsEntryContributor
import ultra.hooks.StreamClientContributor
import ultra.hooks.StreamResolverContributor

/**
 * Read access to the multibound sets for code that runs outside Hilt injection, i.e. the
 * androidx.startup initializer. The sets are returned from functions instead of being injected
 * into constructor parameters on purpose: Kotlin would declare an injected `Set<T>` parameter as
 * `Set<? extends T>`, which does not match Dagger's `Set<T>` binding.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface UltraHookSets {
    fun apiClient(): Set<ApiClientContributor>

    fun streamClient(): Set<StreamClientContributor>

    fun audioProcessors(): Set<AudioProcessorsContributor>

    fun streamResolvers(): Set<StreamResolverContributor>

    fun destinations(): Set<DestinationContributor>

    fun settingsEntries(): Set<SettingsEntryContributor>
}
