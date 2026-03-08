package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.savedstate.compose.serialization.serializers.MutableStateSerializer
import androidx.savedstate.compose.serialization.serializers.SnapshotStateListSerializer
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

internal val navigationSavedStateConfiguration =
    SavedStateConfiguration {
        val screenSerializer = Screen.serializer()
        val screenAsNavKeySerializer =
            object : KSerializer<NavKey> {
                override val descriptor = screenSerializer.descriptor

                override fun serialize(
                    encoder: Encoder,
                    value: NavKey,
                ) = screenSerializer.serialize(encoder, value as Screen)

                override fun deserialize(decoder: Decoder): NavKey = screenSerializer.deserialize(decoder)
            }
        serializersModule =
            SerializersModule {
                polymorphic(NavKey::class) {
                    subclass(Screen::class, screenSerializer)
                    subclass(Screen.Home::class, Screen.Home.serializer())
                    subclass(Screen.Library::class, Screen.Library.serializer())
                    subclass(Screen.Search::class, Screen.Search.serializer())
                    subclass(Screen.EasterEgg::class, Screen.EasterEgg.serializer())
                    subclass(Screen.Settings::class, Screen.Settings.serializer())
                    subclass(Screen.Statistics::class, Screen.Statistics.serializer())
                    subclass(Screen.StatisticsDetail::class, Screen.StatisticsDetail.serializer())
                    subclass(Screen.Recognition::class, Screen.Recognition.serializer())
                    subclass(Screen.RecognitionHistory::class, Screen.RecognitionHistory.serializer())
                    subclass(Screen.Login::class, Screen.Login.serializer())
                    subclass(Screen.TokenLogin::class, Screen.TokenLogin.serializer())
                    subclass(Screen.SwitchChannel::class, Screen.SwitchChannel.serializer())
                    subclass(Screen.Album::class, Screen.Album.serializer())
                    subclass(Screen.Artist::class, Screen.Artist.serializer())
                    subclass(Screen.AboutArtist::class, Screen.AboutArtist.serializer())
                    subclass(Screen.Playlist::class, Screen.Playlist.serializer())
                    subclass(Screen.Podcast::class, Screen.Podcast.serializer())
                    subclass(Screen.HomeSection::class, Screen.HomeSection.serializer())
                    subclass(Screen.Browse::class, Screen.Browse.serializer())
                    subclass(Screen.Integrations::class, Screen.Integrations.serializer())
                    subclass(Screen.Discord::class, Screen.Discord.serializer())
                    subclass(Screen.LastFm::class, Screen.LastFm.serializer())
                    subclass(Screen.ListenBrainz::class, Screen.ListenBrainz.serializer())
                    subclass(Screen.ListenTogether::class, Screen.ListenTogether.serializer())
                    subclass(Screen.ListenTogetherSettings::class, Screen.ListenTogetherSettings.serializer())
                    subclass(Screen.ListenTogetherServer::class, Screen.ListenTogetherServer.serializer())
                    subclass(Screen.ListenTogetherBlockedUsers::class, Screen.ListenTogetherBlockedUsers.serializer())
                    subclass(Screen.ListenTogetherLogs::class, Screen.ListenTogetherLogs.serializer())
                    subclass(Screen.History::class, Screen.History.serializer())
                    subclass(Screen.AndroidAuto::class, Screen.AndroidAuto.serializer())
                    subclass(Screen.Storage::class, Screen.Storage.serializer())
                    subclass(Screen.BackupRestore::class, Screen.BackupRestore.serializer())
                    subclass(Screen.Updates::class, Screen.Updates.serializer())
                    subclass(Screen.UpdateDetails::class, Screen.UpdateDetails.serializer())
                    subclass(Screen.About::class, Screen.About.serializer())
                    subclass(Screen.Devtools::class, Screen.Devtools.serializer())
                    subclass(Screen.PlaybackBenchmark::class, Screen.PlaybackBenchmark.serializer())
                    subclass(Screen.AutoPlaylist::class, Screen.AutoPlaylist.serializer())
                }
                polymorphicDefaultSerializer(NavKey::class) { route ->
                    if (route is Screen) screenAsNavKeySerializer else null
                }
                polymorphicDefaultDeserializer(NavKey::class) { _ ->
                    screenAsNavKeySerializer
                }
            }
    }

@Composable
fun rememberNavigationState(
    startRoute: Screen,
    topLevelRoutes: Set<Screen>,
    initialDestination: Screen = startRoute,
): NavigationState {
    val topLevelRoute =
        rememberSerializable(
            startRoute,
            topLevelRoutes,
            serializer = MutableStateSerializer(Screen.serializer()),
        ) {
            mutableStateOf(startRoute)
        }
    val backStacks =
        topLevelRoutes.associateWith { key ->
            rememberNavBackStack(
                navigationSavedStateConfiguration,
                *if (key == startRoute && initialDestination != key) arrayOf(key, initialDestination) else arrayOf(key),
            )
        }
    val forwardRoutes =
        rememberSerializable(startRoute, topLevelRoutes, serializer = SnapshotStateListSerializer(ForwardRoute.serializer())) {
            mutableStateListOf<ForwardRoute>()
        }

    return remember(startRoute, topLevelRoutes) {
        NavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRoute,
            backStacks = backStacks,
            forwardRoutes = forwardRoutes,
        )
    }
}

@Serializable
data class ForwardRoute(
    val topLevelRoute: Screen,
    val route: Screen,
)

class NavigationState(
    startRoute: Screen,
    topLevelRoute: MutableState<Screen>,
    val backStacks: Map<Screen, NavBackStack<NavKey>>,
    internal val forwardRoutes: SnapshotStateList<ForwardRoute> = mutableStateListOf(),
) {
    val containers = NavigationContainerState()
    var startRoute: Screen by mutableStateOf(startRoute)
    var topLevelRoute: Screen by topLevelRoute

    val currentRoute: Screen
        get() = backStacks.getValue(topLevelRoute).last() as Screen

    val stacksInUse: List<Screen>
        get() = if (topLevelRoute == startRoute) listOf(startRoute) else listOf(startRoute, topLevelRoute)

    internal fun retainedRoutes(root: Screen): List<NavKey> {
        val stack = backStacks.getValue(root)
        return stack +
            forwardRoutes
                .asReversed()
                .filter { it.topLevelRoute == root }
                .map { it.route }
                .filter { it !in stack }
                .distinct()
    }
}

class Navigator(
    val state: NavigationState,
) {
    val canGoBack: Boolean
        get() = state.currentRoute != state.topLevelRoute || state.topLevelRoute != state.startRoute

    val previousRoute: Screen?
        get() {
            val stack = state.backStacks.getValue(state.topLevelRoute)
            return stack.getOrNull(stack.lastIndex - 1) as? Screen
                ?: if (state.topLevelRoute != state.startRoute) state.backStacks.getValue(state.startRoute).last() as Screen else null
        }

    val canGoForward: Boolean
        get() = state.forwardRoutes.isNotEmpty()

    fun navigate(route: Screen) {
        navigate(route, clearForwardRoutes = true)
    }

    fun navigateFrom(
        origin: Screen,
        route: Screen,
    ) {
        if (state.currentRoute == origin) navigate(route)
    }

    fun navigateToRoot(route: Screen) {
        val stack = state.backStacks.getValue(route)
        while (stack.size > 1) stack.removeLastOrNull()
        navigate(route)
    }

    fun goBack(): Boolean {
        if (!canGoBack) return false
        val previous = state.currentRoute
        val currentStack = state.backStacks.getValue(state.topLevelRoute)
        state.forwardRoutes.add(ForwardRoute(state.topLevelRoute, state.currentRoute))

        if (currentStack.last() == state.topLevelRoute) {
            state.topLevelRoute = state.startRoute
        } else {
            currentStack.removeLastOrNull()
        }
        state.containers.navigate(NavigationChange(previous, state.currentRoute, isPop = true))
        return true
    }

    fun goForward(): Boolean {
        val entry = state.forwardRoutes.removeLastOrNull() ?: return false
        navigate(entry.route, clearForwardRoutes = false)
        return true
    }

    private fun navigate(
        requestedRoute: Screen,
        clearForwardRoutes: Boolean,
    ) {
        val previous = state.currentRoute
        val route =
            if (requestedRoute is Screen.Album && navigationPattern(previous, requestedRoute) == NavigationPattern.Expressive) {
                requestedRoute.copy(artworkSource = requestedRoute.artworkSource ?: state.containers.pendingAlbumArtworkSource)
            } else {
                requestedRoute
            }
        if (clearForwardRoutes) state.forwardRoutes.clear()
        if (route in state.backStacks) {
            if (route == state.topLevelRoute) {
                val stack = state.backStacks.getValue(route)
                while (stack.size > 1) stack.removeLastOrNull()
            }
            state.topLevelRoute = route
        } else {
            val stack = state.backStacks[state.topLevelRoute] ?: return
            if (stack.lastOrNull() != route) stack.add(route)
        }
        state.containers.navigate(NavigationChange(previous, state.currentRoute), reuseOrigin = !clearForwardRoutes)
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No Navigator provided") }

@Composable
fun NavigationState.toEntries(
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    entryDecorators: @Composable () -> List<NavEntryDecorator<NavKey>> = { emptyList() },
): List<NavEntry<NavKey>> {
    val decoratedEntries =
        backStacks.mapValues { (root, stack) ->
            key(root) {
                rememberDecoratedNavEntries(
                    backStack = retainedRoutes(root),
                    entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>()) + entryDecorators(),
                    entryProvider = entryProvider,
                ).take(stack.size)
            }
        }

    return stacksInUse.flatMap { decoratedEntries[it].orEmpty() }
}
