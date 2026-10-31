package ultra.wiring.di

import dagger.Module
import dagger.multibindings.Multibinds
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import ultra.hooks.ApiClientContributor
import ultra.hooks.AudioProcessorsContributor
import ultra.hooks.DestinationContributor
import ultra.hooks.SettingsEntryContributor
import ultra.hooks.StreamClientContributor
import ultra.hooks.StreamResolverContributor

/**
 * Declares one (initially empty) multibound set per seam contract. Declaring them here, once,
 * means the sets exist and are injectable even while no feature module contributes anything,
 * and feature modules only ever add `@Binds @IntoSet` entries.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class UltraHookSetsModule {
    @Multibinds
    abstract fun apiClientContributors(): Set<ApiClientContributor>

    @Multibinds
    abstract fun streamClientContributors(): Set<StreamClientContributor>

    @Multibinds
    abstract fun audioProcessorsContributors(): Set<AudioProcessorsContributor>

    @Multibinds
    abstract fun streamResolverContributors(): Set<StreamResolverContributor>

    @Multibinds
    abstract fun destinationContributors(): Set<DestinationContributor>

    @Multibinds
    abstract fun settingsEntryContributors(): Set<SettingsEntryContributor>
}
