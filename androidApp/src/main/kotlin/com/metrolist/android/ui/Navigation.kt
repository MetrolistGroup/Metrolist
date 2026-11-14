package com.metrolist.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.filter_library
import com.metrolist.shared.generated.resources.home
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.menu_history
import com.metrolist.shared.generated.resources.mysterious_fourth_tab
import com.metrolist.shared.generated.resources.recognize_nearby_music
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.statistics
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.AdaptiveLayout
import com.metrolist.shared.ui.navigation.AdaptiveNavigationItem
import com.metrolist.shared.ui.navigation.AdaptiveNavigationScaffold
import com.metrolist.shared.ui.navigation.LocalUseDesktopUi
import com.metrolist.shared.ui.navigation.NavigationState
import com.metrolist.shared.ui.navigation.Navigator
import com.metrolist.shared.ui.navigation.Screen
import com.metrolist.shared.ui.navigation.isNavigationBar
import com.metrolist.shared.ui.navigation.visibleSidebarScreens
import com.metrolist.shared.ui.tv.tvFocusHighlight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

val Screen.labelRes: StringResource?
    get() =
        when (this) {
            Screen.Home -> Res.string.home
            Screen.Library -> Res.string.filter_library
            Screen.Search -> Res.string.search
            Screen.EasterEgg -> Res.string.mysterious_fourth_tab
            Screen.Settings -> Res.string.settings
            Screen.History -> Res.string.menu_history
            Screen.Statistics -> Res.string.statistics
            Screen.ListenTogether -> Res.string.listen_together
            else -> null
        }

val Screen.icon: ImageVector?
    get() =
        when (this) {
            Screen.Home -> MetrolistIcons.Outlined.Home
            Screen.Library -> MetrolistIcons.Outlined.LibraryMusic
            Screen.Search -> MetrolistIcons.Outlined.Search
            Screen.Settings -> MetrolistIcons.Outlined.Settings
            Screen.History -> MetrolistIcons.Outlined.History
            Screen.Statistics -> MetrolistIcons.Outlined.SsidChart
            Screen.ListenTogether -> MetrolistIcons.Outlined.Group
            else -> null
        }

val Screen.iconFilled: ImageVector?
    get() =
        when (this) {
            Screen.Home -> MetrolistIcons.Filled.Home
            Screen.Library -> MetrolistIcons.Filled.LibraryMusic
            Screen.Search -> MetrolistIcons.Filled.Search
            Screen.EasterEgg -> MetrolistIcons.Filled.QuestionMark
            Screen.Settings -> MetrolistIcons.Filled.Settings
            Screen.History -> MetrolistIcons.Filled.History
            Screen.Statistics -> MetrolistIcons.Filled.SsidChart
            Screen.ListenTogether -> MetrolistIcons.Outlined.Group
            else -> null
        }

@Composable
fun MetrolistNavigationScaffold(
    navigator: Navigator,
    navigationState: NavigationState,
    adaptiveLayout: AdaptiveLayout,
    navigationSuiteType: NavigationSuiteType,
    navigationVisible: Boolean,
    containerColor: Color,
    onSearchLongClick: () -> Unit,
    floatingNavigation: Boolean = false,
    onFloatingNavigationOccupiedHeightChanged: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
    selectedItemFocusRequester: FocusRequester = FocusRequester.Default,
    bottomExitFocusRequester: FocusRequester = FocusRequester.Default,
    contentFocusRequester: FocusRequester = FocusRequester.Default,
    onScrollToTop: (Screen) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val useDesktopUi = LocalUseDesktopUi.current
    val isTv = LocalIsTv.current
    val useFloatingNavigation = floatingNavigation && !useDesktopUi && !isTv
    val screens =
        if (useDesktopUi) {
            (visibleSidebarScreens(Settings.shared.sidebarNavItems) + Screen.Search + Screen.Settings).distinct()
        } else if (useFloatingNavigation) {
            floatingNavigationItems()
        } else {
            visibleNavigationItems()
        }
    val layoutDirection = LocalLayoutDirection.current
    val selectedScreen =
        (navigationState.currentRoute.takeIf { it in screens } ?: navigationState.topLevelRoute)
            .takeIf { it in screens } ?: screens.first()
    val items =
        screens.mapNotNull { screen ->
            val label = screen.labelRes?.let { stringResource(it) } ?: return@mapNotNull null
            val icon = screen.icon ?: screen.iconFilled ?: return@mapNotNull null
            AdaptiveNavigationItem(
                key = screen,
                label = label,
                icon = icon,
                selectedIcon = screen.iconFilled ?: icon,
            )
        }
    val hapticManager = rememberMetrolistHaptics()
    val scope = rememberCoroutineScope()
    var suppressSearchClick by remember { mutableStateOf(false) }
    val recognizeNearbyMusic = stringResource(Res.string.recognize_nearby_music)
    val usesNavigationRail = !navigationSuiteType.isNavigationBar
    val itemFocusRequesters = remember(screens) { screens.map { FocusRequester() } }

    fun searchLongPressModifier(screen: Screen): Modifier =
        if (screen == Screen.Search && Settings.shared.voiceSearchEnabled) {
            Modifier
                .semantics {
                    customActions =
                        listOf(
                            CustomAccessibilityAction(recognizeNearbyMusic) {
                                hapticManager.perform(HapticEvent.LONG_PRESS)
                                onSearchLongClick()
                                true
                            },
                        )
                }.navigationLongPress(
                    onLongPress = {
                        suppressSearchClick = true
                        hapticManager.perform(HapticEvent.LONG_PRESS)
                        onSearchLongClick()
                    },
                    onLongPressRelease = {
                        scope.launch {
                            delay(100.milliseconds)
                            suppressSearchClick = false
                        }
                    },
                )
        } else {
            Modifier
        }

    AdaptiveNavigationScaffold(
        layout = adaptiveLayout,
        items = items,
        selectedKey = selectedScreen,
        onSelect = { screen ->
            if (screen == Screen.Search && suppressSearchClick) {
                suppressSearchClick = false
            } else {
                hapticManager.perform(HapticEvent.PRESS)
                val shouldScrollToTop =
                    navigationState.topLevelRoute == screen &&
                        (screen == Screen.Home || screen == Screen.Library)
                navigator.navigate(screen)
                if (shouldScrollToTop) onScrollToTop(screen)
            }
        },
        modifier = modifier.background(containerColor),
        navigationVisible = navigationVisible,
        floatingNavigation = useFloatingNavigation,
        onFloatingNavigationOccupiedHeightChanged = onFloatingNavigationOccupiedHeightChanged,
        floatingSeparateKey = Screen.Search.takeIf { it in screens },
        navigationSuiteType = navigationSuiteType,
        navigationSuiteColors =
            NavigationSuiteDefaults.colors(
                navigationBarContainerColor = Color.Transparent,
                navigationRailContainerColor = Color.Transparent,
            ),
        containerColor = Color.Transparent,
        isSidebarExpanded = false,
        isSidebarExpandable = false,
        navigationItemVerticalArrangement = Arrangement.Center,
        itemModifier = { screen ->
            val index = screens.indexOf(screen)
            searchLongPressModifier(screen)
                .focusProperties { canFocus = navigationVisible }
                .tvFocusHighlight(
                    shape = RoundedCornerShape(16.dp),
                    scale = 1.04f,
                    bringIntoView = false,
                ).then(
                    if (usesNavigationRail && isTv) {
                        Modifier.focusRequester(itemFocusRequesters[index]).focusProperties {
                            up = itemFocusRequesters.getOrNull(index - 1) ?: FocusRequester.Cancel
                            down = itemFocusRequesters.getOrNull(index + 1) ?: bottomExitFocusRequester
                            left = if (layoutDirection == LayoutDirection.Ltr) FocusRequester.Cancel else contentFocusRequester
                            right = if (layoutDirection == LayoutDirection.Ltr) contentFocusRequester else FocusRequester.Cancel
                        }
                    } else if (usesNavigationRail && index == screens.lastIndex) {
                        Modifier.focusProperties { down = bottomExitFocusRequester }
                    } else {
                        Modifier
                    },
                ).then(
                    if (usesNavigationRail && selectedScreen == screen) {
                        Modifier.focusRequester(selectedItemFocusRequester)
                    } else {
                        Modifier
                    },
                )
        },
        content = { content() },
    )
}

private fun floatingNavigationItems(): List<Screen> =
    buildList {
        if (Settings.shared.showBottomNavHome) add(Screen.Home)
        if (Settings.shared.showBottomNavLibrary) add(Screen.Library)
        if (Settings.shared.showBottomNavSearch) add(Screen.Search)
        if (isEmpty()) add(Screen.EasterEgg)
    }

private fun visibleNavigationItems(): List<Screen> =
    buildList {
        if (Settings.shared.showBottomNavHome) add(Screen.Home)
        if (Settings.shared.showBottomNavSearch) add(Screen.Search)
        if (Settings.shared.showBottomNavLibrary) add(Screen.Library)
        if (isEmpty()) add(Screen.EasterEgg)
    }

private fun Modifier.navigationLongPress(
    onLongPress: () -> Unit,
    onLongPressRelease: () -> Unit,
): Modifier {
    var keyLongPressHandled = false
    return pointerInput(onLongPress) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            val releasedBeforeLongPress =
                withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation()
                    true
                }
            if (releasedBeforeLongPress == null) {
                onLongPress()
                waitForUpOrCancellation()
                onLongPressRelease()
            }
        }
    }.onPreviewKeyEvent { event ->
        val isActivationKey = event.key == Key.DirectionCenter || event.key == Key.Enter
        if (!isActivationKey) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyUp && keyLongPressHandled) {
            keyLongPressHandled = false
            onLongPressRelease()
            return@onPreviewKeyEvent true
        }
        if (
            event.type == KeyEventType.KeyDown &&
            event.nativeKeyEvent.repeatCount > 0 &&
            !keyLongPressHandled
        ) {
            keyLongPressHandled = true
            onLongPress()
            true
        } else {
            false
        }
    }
}
