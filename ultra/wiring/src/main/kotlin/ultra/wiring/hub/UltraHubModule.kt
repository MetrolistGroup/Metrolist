package ultra.wiring.hub

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import ultra.hooks.DestinationContributor
import ultra.hooks.SettingsEntryContributor

/** Contributes the hub to the S8 and S9 sets. This is the template every feature module follows. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UltraHubModule {
    @Binds
    @IntoSet
    abstract fun destinations(impl: UltraHubContributor): DestinationContributor

    @Binds
    @IntoSet
    abstract fun settingsEntries(impl: UltraHubContributor): SettingsEntryContributor
}
