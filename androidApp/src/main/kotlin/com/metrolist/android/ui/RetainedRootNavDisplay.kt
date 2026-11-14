package com.metrolist.android.ui

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.rememberLifecycleOwner
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.scene.DialogSceneStrategy
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.navigation.NavigationState
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.toEntries

@Composable
internal fun RetainedRootNavDisplay(
    navigationState: NavigationState,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
    onBack: () -> Unit,
) {
    val roots = remember { listOf(Screen.Home, Screen.Library) }
    val rootEntries = remember(entryProvider) { roots.associateWith(entryProvider) }
    val sceneEntryProvider =
        remember(entryProvider) {
            { route: NavKey ->
                val entry = entryProvider(route)
                if (route in roots) NavEntry(navEntry = entry) { Box(Modifier.fillMaxSize()) } else entry
            }
        }
    val entries = navigationState.toEntries(sceneEntryProvider) { listOf(rememberDetailViewModelNavEntryDecorator()) }
    val sceneState = rememberSceneState(entries, remember { listOf(DialogSceneStrategy()) }, onBack = onBack)
    val navigationEvents = rememberNavigationEventState(sceneState)
    NavigationBackHandler(sceneState, navigationEvents, onBackCompleted = onBack)
    val predictingBack = navigationEvents.transitionState is InProgress
    val visibleScene = if (predictingBack) sceneState.previousScenes.lastOrNull() ?: sceneState.currentScene else sceneState.currentScene
    val visibleRoot = roots.firstOrNull { root -> visibleScene.entries.any { it.contentKey == rootEntries.getValue(root).contentKey } }
    val activeRoot = visibleRoot.takeUnless { predictingBack || sceneState.overlayScenes.isNotEmpty() }
    var visited by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val slots = (visited + listOfNotNull(visibleRoot?.toString())).distinct()
    SideEffect { if (visited != slots) visited = slots }
    val currentVisibleRoot by rememberUpdatedState(visibleRoot)
    val context = LocalContext.current.applicationContext
    val stateHolder = rememberSaveableStateHolder()
    DisposableEffect(context) {
        val callback =
            object : ComponentCallbacks2 {
                private fun evictHiddenRoots() {
                    visited = listOfNotNull(currentVisibleRoot?.toString())
                }

                @Suppress("DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) evictHiddenRoots()
                }

                @Deprecated("Required by ComponentCallbacks on Android versions before API 34")
                override fun onLowMemory() = evictHiddenRoots()

                override fun onConfigurationChanged(newConfig: Configuration) = Unit
            }
        context.registerComponentCallbacks(callback)
        onDispose { context.unregisterComponentCallbacks(callback) }
    }
    Box(Modifier.fillMaxSize()) {
        roots.filter { it.toString() in slots }.forEach { root ->
            key(root) {
                val active = root == activeRoot
                val owner = rememberLifecycleOwner(maxLifecycle = if (active) Lifecycle.State.RESUMED else Lifecycle.State.CREATED)
                CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalRootContentActive provides active) {
                    stateHolder.SaveableStateProvider(root.toString()) {
                        ParkedRoot(visible = root == visibleRoot, active = active) {
                            rootEntries.getValue(root).Content()
                        }
                    }
                }
            }
        }
        NavDisplay(
            sceneState = sceneState,
            navigationEventState = navigationEvents,
            transitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            popTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
            predictivePopTransitionSpec = { EnterTransition.None togetherWith ExitTransition.None },
        )
    }
}

@Composable
private fun ParkedRoot(
    visible: Boolean,
    active: Boolean,
    content: @Composable () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active && hasFocus) focusManager.clearFocus(force = true)
    }
    Layout(
        content = content,
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (active) Modifier else Modifier.clearAndSetSemantics { })
                .onPreviewKeyEvent { !active }
                .onFocusChanged { hasFocus = it.hasFocus }
                .focusProperties { canFocus = active }
                .focusGroup(),
    ) { measurables, constraints ->
        // Do not measure hidden children: skipping only placement still pays for the whole root.
        val children = if (visible) measurables.map { it.measure(constraints) } else emptyList()
        layout(constraints.maxWidth, constraints.maxHeight) {
            children.forEach { it.place(0, 0) }
        }
    }
}
