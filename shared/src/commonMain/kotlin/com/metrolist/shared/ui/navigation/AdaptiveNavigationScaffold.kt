@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.navigation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationItemIconPosition
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarArrangement
import androidx.compose.material3.ShortNavigationBarDefaults
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.WideNavigationRail
import androidx.compose.material3.WideNavigationRailDefaults
import androidx.compose.material3.WideNavigationRailItem
import androidx.compose.material3.WideNavigationRailItemDefaults
import androidx.compose.material3.WideNavigationRailValue
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteColors
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldValue
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.material3.rememberWideNavigationRailState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import com.metrolist.shared.constants.WideNavigationRailCollapsedWidth
import com.metrolist.shared.constants.WideNavigationRailExpandedWidth
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.drag_to_reorder_x
import com.metrolist.shared.generated.resources.expand_sidebar
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.DragScrollableColumn
import org.jetbrains.compose.resources.stringResource
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

class AdaptiveNavigationItem<T>(
    val key: T,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector = icon,
    val visible: Boolean = true,
)

enum class AdaptiveFormFactor {
    TOUCH,

    DESKTOP,
    TELEVISION,

    AUTOMOTIVE,
}

enum class AdaptiveWidthBreakpoint {
    COMPACT,
    MEDIUM,
    EXPANDED,
    LARGE,
    EXTRA_LARGE,
}

enum class AdaptiveHeightBreakpoint {
    COMPACT,
    MEDIUM,
    EXPANDED,
}

@Immutable
data class AdaptiveLayout(
    val formFactor: AdaptiveFormFactor,
    val widthBreakpoint: AdaptiveWidthBreakpoint,
    val heightBreakpoint: AdaptiveHeightBreakpoint,
    val recommendedPaneCount: Int,
    val maximumPaneCount: Int,
    val navigationSuiteType: NavigationSuiteType,
)

val NavigationSuiteType.isNavigationBar: Boolean
    get() =
        (this == NavigationSuiteType.ShortNavigationBarCompact) ||
            (this == NavigationSuiteType.ShortNavigationBarMedium) ||
            (this == NavigationSuiteType.NavigationBar)

val NavigationSuiteType.isShortNavigationBar: Boolean
    get() =
        (this == NavigationSuiteType.ShortNavigationBarCompact) ||
            (this == NavigationSuiteType.ShortNavigationBarMedium)

@Composable
fun currentAdaptiveLayout(
    formFactor: AdaptiveFormFactor,
    forceBottomNavBar: Boolean = false,
): AdaptiveLayout {
    val adaptiveInfo = currentWindowAdaptiveInfoV2()
    return calculateAdaptiveLayout(
        formFactor = formFactor,
        windowSizeClass = adaptiveInfo.windowSizeClass,
        isTabletop = adaptiveInfo.windowPosture.isTabletop,
        forceBottomNavBar = forceBottomNavBar,
    )
}

internal fun calculateAdaptiveLayout(
    formFactor: AdaptiveFormFactor,
    minWidthDp: Int,
    minHeightDp: Int,
    isTabletop: Boolean = false,
    forceBottomNavBar: Boolean = false,
): AdaptiveLayout =
    calculateAdaptiveLayout(
        formFactor = formFactor,
        windowSizeClass = WindowSizeClass(minWidthDp, minHeightDp),
        isTabletop = isTabletop,
        forceBottomNavBar = forceBottomNavBar,
    )

internal fun calculateAdaptiveLayout(
    formFactor: AdaptiveFormFactor,
    windowSizeClass: WindowSizeClass,
    isTabletop: Boolean = false,
    forceBottomNavBar: Boolean = false,
): AdaptiveLayout {
    val widthBreakpoint =
        when {
            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXTRA_LARGE_LOWER_BOUND) -> {
                AdaptiveWidthBreakpoint.EXTRA_LARGE
            }

            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_LARGE_LOWER_BOUND) -> {
                AdaptiveWidthBreakpoint.LARGE
            }

            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND) -> {
                AdaptiveWidthBreakpoint.EXPANDED
            }

            windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND) -> {
                AdaptiveWidthBreakpoint.MEDIUM
            }

            else -> {
                AdaptiveWidthBreakpoint.COMPACT
            }
        }
    val heightBreakpoint =
        when {
            windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_EXPANDED_LOWER_BOUND) -> {
                AdaptiveHeightBreakpoint.EXPANDED
            }

            windowSizeClass.isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND) -> {
                AdaptiveHeightBreakpoint.MEDIUM
            }

            else -> {
                AdaptiveHeightBreakpoint.COMPACT
            }
        }
    val hasRoomForMultiplePanes =
        (heightBreakpoint != AdaptiveHeightBreakpoint.COMPACT) &&
            (widthBreakpoint >= AdaptiveWidthBreakpoint.EXPANDED)
    val recommendedPaneCount = if (hasRoomForMultiplePanes) 2 else 1
    val maximumPaneCount =
        when {
            heightBreakpoint == AdaptiveHeightBreakpoint.COMPACT -> 1
            widthBreakpoint == AdaptiveWidthBreakpoint.EXTRA_LARGE -> 3
            widthBreakpoint >= AdaptiveWidthBreakpoint.MEDIUM -> 2
            else -> 1
        }
    val automaticNavigationType =
        when {
            widthBreakpoint == AdaptiveWidthBreakpoint.COMPACT -> NavigationSuiteType.ShortNavigationBarCompact
            heightBreakpoint == AdaptiveHeightBreakpoint.COMPACT || isTabletop -> NavigationSuiteType.ShortNavigationBarMedium
            else -> NavigationSuiteType.WideNavigationRailCollapsed
        }
    val navigationSuiteType =
        when (formFactor) {
            AdaptiveFormFactor.TELEVISION -> {
                NavigationSuiteType.WideNavigationRailCollapsed
            }

            AdaptiveFormFactor.AUTOMOTIVE -> {
                if (!automaticNavigationType.isNavigationBar) {
                    NavigationSuiteType.WideNavigationRailExpanded
                } else {
                    automaticNavigationType
                }
            }

            else -> {
                automaticNavigationType
            }
        }

    return AdaptiveLayout(
        formFactor = formFactor,
        widthBreakpoint = widthBreakpoint,
        heightBreakpoint = heightBreakpoint,
        recommendedPaneCount = recommendedPaneCount,
        maximumPaneCount = maximumPaneCount,
        navigationSuiteType =
            if (forceBottomNavBar) {
                if (widthBreakpoint == AdaptiveWidthBreakpoint.COMPACT) {
                    NavigationSuiteType.ShortNavigationBarCompact
                } else {
                    NavigationSuiteType.ShortNavigationBarMedium
                }
            } else {
                navigationSuiteType
            },
    )
}

@Composable
fun <T> AdaptiveNavigationScaffold(
    layout: AdaptiveLayout,
    items: List<AdaptiveNavigationItem<T>>,
    selectedKey: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    navigationVisible: Boolean = true,
    floatingNavigation: Boolean = false,
    floatingSeparateKey: T? = null,
    onFloatingNavigationHeightChanged: (Dp) -> Unit = {},
    onFloatingNavigationOccupiedHeightChanged: (Dp) -> Unit = {},
    navigationSuiteType: NavigationSuiteType = layout.navigationSuiteType,
    navigationSuiteColors: NavigationSuiteColors = NavigationSuiteDefaults.colors(),
    containerColor: Color = Color.Transparent,
    navigationItemVerticalArrangement: Arrangement.Vertical = Arrangement.Top,
    itemModifier: @Composable (T) -> Modifier = { Modifier },
    navigationHeader: @Composable (NavigationSuiteType) -> Unit = {},
    navigationFooter: (@Composable (isExpanded: Boolean) -> Unit)? = null,
    isSidebarExpanded: Boolean = true,
    isSidebarExpandable: Boolean = true,
    onToggleSidebar: () -> Unit = {},
    isEditing: Boolean = false,
    onReorderFinished: ((List<T>) -> Unit)? = null,
    onToggleVisibility: ((T) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val visibleItems = if (isEditing) items else items.filter { it.visible }
    val navigationItems: @Composable () -> Unit = {
        visibleItems.forEach { item ->
            val selected = item.key == selectedKey
            NavigationSuiteItem(
                selected = selected,
                onClick = { onSelect(item.key) },
                icon = {
                    AnimatedContent(
                        targetState = selected,
                        transitionSpec = {
                            (fadeIn() + scaleIn(initialScale = 0.8f)) togetherWith
                                (fadeOut() + scaleOut(targetScale = 0.8f))
                        },
                        contentKey = { it },
                    ) { isSelected ->
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.icon,
                            contentDescription = null,
                        )
                    }
                },
                label = { Text(item.label, fontFamily = MaterialTheme.typography.labelLarge.fontFamily) },
                modifier = itemModifier(item.key),
                navigationSuiteType = navigationSuiteType,
            )
        }
    }

    val state =
        rememberNavigationSuiteScaffoldState(
            if (navigationVisible) NavigationSuiteScaffoldValue.Visible else NavigationSuiteScaffoldValue.Hidden,
        )
    LaunchedEffect(navigationVisible) {
        state.snapTo(
            if (navigationVisible) NavigationSuiteScaffoldValue.Visible else NavigationSuiteScaffoldValue.Hidden,
        )
    }

    val desktopRail =
        layout.formFactor == AdaptiveFormFactor.DESKTOP &&
            (
                navigationSuiteType == NavigationSuiteType.WideNavigationRailExpanded ||
                    navigationSuiteType == NavigationSuiteType.WideNavigationRailCollapsed
            )
    val floatingBar = floatingNavigation && navigationSuiteType.isNavigationBar
    val slimBar = !floatingBar && navigationSuiteType.isShortNavigationBar
    val customNavigation = desktopRail || floatingBar || slimBar
    val density = LocalDensity.current

    // Keep the screen under one lookahead parent when navigation preferences or window size change.
    NavigationSuiteScaffold(
        navigationItems = navigationItems,
        modifier = modifier,
        navigationSuiteType = if (customNavigation) NavigationSuiteType.None else navigationSuiteType,
        navigationSuiteColors = navigationSuiteColors,
        state = state,
        containerColor = if (customNavigation) Color.Transparent else containerColor,
        navigationItemVerticalArrangement = navigationItemVerticalArrangement,
        primaryActionContent = { if (!customNavigation) navigationHeader(navigationSuiteType) },
    ) {
        Row(Modifier.fillMaxSize()) {
            if (desktopRail) {
                DesktopWideNavigationRail(
                    items = items,
                    visibleItems = visibleItems,
                    selectedKey = selectedKey,
                    onSelect = onSelect,
                    navigationHeader = navigationHeader,
                    navigationFooter = navigationFooter,
                    isSidebarExpanded = isSidebarExpanded,
                    isSidebarExpandable = isSidebarExpandable,
                    onToggleSidebar = onToggleSidebar,
                    isEditing = isEditing,
                    onReorderFinished = onReorderFinished,
                    onToggleVisibility = onToggleVisibility,
                )
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .then(if (desktopRail) Modifier.background(MaterialTheme.colorScheme.surfaceContainer) else Modifier),
                ) {
                    Box(Modifier.fillMaxSize().then(if (floatingBar || slimBar) Modifier.clipToBounds() else Modifier)) {
                        // Custom navigation layouts use the same content color as a Material surface.
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface, content = content)
                    }
                    if (floatingBar) {
                        // Keep reporting zero height after the exit removes the animated bar.
                        Box(
                            modifier =
                                Modifier.align(Alignment.BottomCenter).onSizeChanged { size ->
                                    onFloatingNavigationOccupiedHeightChanged(with(density) { size.height.toDp() })
                                },
                        ) {
                            FloatingNavigation(
                                items = visibleItems,
                                selectedKey = selectedKey,
                                onSelect = onSelect,
                                navigationVisible = navigationVisible,
                                separateKey = floatingSeparateKey,
                                itemModifier = itemModifier,
                                onBarHeightChanged = onFloatingNavigationHeightChanged,
                            )
                        }
                    }
                }
                if (slimBar) {
                    SlimNavigationBar(
                        items = visibleItems,
                        selectedKey = selectedKey,
                        onSelect = onSelect,
                        navigationVisible = navigationVisible,
                        containerColor = containerColor,
                        itemModifier = itemModifier,
                        navigationSuiteType = navigationSuiteType,
                    )
                }
            }
        }
    }
}

@Composable
private fun <T> DesktopWideNavigationRail(
    items: List<AdaptiveNavigationItem<T>>,
    visibleItems: List<AdaptiveNavigationItem<T>>,
    selectedKey: T?,
    onSelect: (T) -> Unit,
    navigationHeader: @Composable (NavigationSuiteType) -> Unit,
    navigationFooter: (@Composable (isExpanded: Boolean) -> Unit)?,
    isSidebarExpanded: Boolean,
    isSidebarExpandable: Boolean,
    onToggleSidebar: () -> Unit,
    isEditing: Boolean,
    onReorderFinished: ((List<T>) -> Unit)?,
    onToggleVisibility: ((T) -> Unit)?,
) {
    val railState =
        rememberWideNavigationRailState(
            initialValue = if (isSidebarExpanded) WideNavigationRailValue.Expanded else WideNavigationRailValue.Collapsed,
        )
    LaunchedEffect(isSidebarExpanded) {
        if (isSidebarExpanded) railState.expand() else railState.collapse()
    }
    val animatedWidth by animateDpAsState(
        targetValue = if (isSidebarExpanded) WideNavigationRailExpandedWidth else WideNavigationRailCollapsedWidth,
        animationSpec =
            spring(
                dampingRatio = 0.82f,
                stiffness = 380f,
            ),
        label = "sidebarWidth",
    )

    Row {
        Surface(
            modifier =
                Modifier
                    .width(animatedWidth)
                    .fillMaxHeight(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    WideNavigationRail(
                        state = railState,
                        modifier = Modifier.fillMaxSize(),
                        header = {
                            if (!isSidebarExpanded && isSidebarExpandable) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    IconButton(
                                        onClick = onToggleSidebar,
                                        modifier = Modifier.size(48.dp),
                                        shapes = IconButtonDefaults.shapes(),
                                    ) {
                                        Icon(
                                            MetrolistIcons.Filled.Menu,
                                            contentDescription = stringResource(Res.string.expand_sidebar),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(24.dp),
                                        )
                                    }
                                }
                            } else {
                                navigationHeader(
                                    if (isSidebarExpanded) {
                                        NavigationSuiteType.WideNavigationRailExpanded
                                    } else {
                                        NavigationSuiteType.WideNavigationRailCollapsed
                                    },
                                )
                            }
                        },
                        colors =
                            WideNavigationRailDefaults.colors(
                                containerColor = Color.Transparent,
                            ),
                        contentPadding =
                            if (isSidebarExpanded) {
                                PaddingValues(horizontal = 4.dp)
                            } else {
                                PaddingValues(0.dp)
                            },
                    ) {
                        if (isEditing && onReorderFinished != null && onToggleVisibility != null) {
                            val haptics = rememberMetrolistHaptics()
                            val itemKeys = items.map { it.key }
                            var itemOrder by remember(itemKeys) { mutableStateOf(itemKeys) }
                            var pendingReorder by remember { mutableStateOf(false) }
                            val editingItems = itemOrder.mapNotNull { key -> items.firstOrNull { it.key == key } }
                            val lazyListState = rememberLazyListState()
                            val reorderableState =
                                rememberReorderableLazyListState(lazyListState) { from, to ->
                                    itemOrder = itemOrder.toMutableList().apply { add(to.index, removeAt(from.index)) }
                                    pendingReorder = true
                                    haptics.perform(HapticEvent.DRAG_SNAP)
                                }
                            LaunchedEffect(reorderableState.isAnyItemDragging) {
                                if (!reorderableState.isAnyItemDragging && pendingReorder) {
                                    pendingReorder = false
                                    if (itemOrder != itemKeys) onReorderFinished(itemOrder)
                                }
                            }
                            DragScrollableColumn(
                                state = lazyListState,
                                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            ) {
                                itemsIndexed(editingItems, key = { _, item -> item.key.toString() }) { _, item ->
                                    ReorderableItem(reorderableState, key = item.key.toString()) { isDragging ->
                                        EditingSidebarNavItem(
                                            item = item,
                                            onToggleVisibility = { onToggleVisibility(item.key) },
                                            expanded = isSidebarExpanded,
                                            isDragging = isDragging,
                                            modifier =
                                                Modifier.draggableHandle(
                                                    onDragStarted = { haptics.perform(HapticEvent.DRAG_START) },
                                                ),
                                        )
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(if (isSidebarExpanded) 0.dp else 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                visibleItems.forEach { item ->
                                    val selected = item.key == selectedKey
                                    val icon: @Composable () -> Unit = {
                                        AnimatedContent(
                                            targetState = selected,
                                            contentAlignment = Alignment.Center,
                                            transitionSpec = {
                                                (
                                                    fadeIn(animationSpec = tween(180)) +
                                                        scaleIn(
                                                            initialScale = 0.8f,
                                                            animationSpec =
                                                                spring(
                                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                                    stiffness = Spring.StiffnessMediumLow,
                                                                ),
                                                        )
                                                ) togetherWith (
                                                    fadeOut(animationSpec = tween(120)) +
                                                        scaleOut(
                                                            targetScale = 0.85f,
                                                            animationSpec = tween(120),
                                                        )
                                                )
                                            },
                                            contentKey = { it },
                                        ) { isSelected ->
                                            Icon(
                                                imageVector = if (isSelected) item.selectedIcon else item.icon,
                                                contentDescription = null,
                                            )
                                        }
                                    }
                                    if (isSidebarExpanded) {
                                        NavigationDrawerItem(
                                            selected = selected,
                                            onClick = { onSelect(item.key) },
                                            icon = icon,
                                            label = {
                                                Text(
                                                    item.label,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                    } else {
                                        WideNavigationRailItem(
                                            selected = selected,
                                            onClick = { onSelect(item.key) },
                                            icon = icon,
                                            label = {
                                                Text(
                                                    text = item.label,
                                                    textAlign = TextAlign.Center,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            },
                                            railExpanded = false,
                                            iconPosition = NavigationItemIconPosition.Top,
                                            colors =
                                                WideNavigationRailItemDefaults.colors(
                                                    selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    selectedTextColorTopIconPosition = MaterialTheme.colorScheme.onSurface,
                                                    selectedTextColorStartIconPosition = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    selectedIndicatorColor = MaterialTheme.colorScheme.secondaryContainer,
                                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                                ),
                                            modifier = Modifier.heightIn(min = 64.dp).padding(vertical = 4.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                if (navigationFooter != null) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        navigationFooter(isSidebarExpanded)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ColumnScope.SlimNavigationBar(
    items: List<AdaptiveNavigationItem<T>>,
    selectedKey: T?,
    onSelect: (T) -> Unit,
    navigationVisible: Boolean,
    containerColor: Color,
    itemModifier: @Composable (T) -> Modifier,
    navigationSuiteType: NavigationSuiteType,
) {
    AnimatedVisibility(
        visible = navigationVisible,
        enter =
            expandVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                expandFrom = Alignment.Bottom,
            ),
        exit =
            shrinkVertically(
                animationSpec = spring(dampingRatio = 0.9f, stiffness = 700f),
                shrinkTowards = Alignment.Bottom,
            ),
    ) {
        SlimNavigationBarContent(
            items = items,
            selectedKey = selectedKey,
            onSelect = onSelect,
            containerColor = containerColor,
            itemModifier = itemModifier,
            isCompact = navigationSuiteType == NavigationSuiteType.ShortNavigationBarCompact,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> SlimNavigationBarContent(
    items: List<AdaptiveNavigationItem<T>>,
    selectedKey: T?,
    onSelect: (T) -> Unit,
    containerColor: Color,
    itemModifier: @Composable (T) -> Modifier,
    isCompact: Boolean,
) {
    ShortNavigationBar(
        modifier =
            Modifier
                .background(containerColor)
                .windowInsetsPadding(ShortNavigationBarDefaults.windowInsets)
                .height(56.dp),
        containerColor = containerColor,
        windowInsets = WindowInsets(0),
        arrangement =
            if (isCompact) {
                ShortNavigationBarArrangement.EqualWeight
            } else {
                ShortNavigationBarArrangement.Centered
            },
    ) {
        items.forEach { item ->
            val selected = item.key == selectedKey
            ShortNavigationBarItem(
                selected = selected,
                onClick = { onSelect(item.key) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.icon,
                        contentDescription = item.label,
                    )
                },
                label = null,
                iconPosition =
                    if (isCompact) {
                        NavigationItemIconPosition.Top
                    } else {
                        NavigationItemIconPosition.Start
                    },
                modifier = itemModifier(item.key),
            )
        }
    }
}

@Composable
private fun <T> EditingSidebarNavItem(
    item: AdaptiveNavigationItem<T>,
    onToggleVisibility: () -> Unit,
    expanded: Boolean,
    isDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val contentColor = if (item.visible) colorScheme.onSurface else colorScheme.onSurface.copy(alpha = 0.3f)
    val handleInteractionSource = remember { MutableInteractionSource() }
    val isHandleHovered by handleInteractionSource.collectIsHoveredAsState()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(
                    color = if (isDragging) colorScheme.secondaryContainer else Color.Transparent,
                    shape = MaterialTheme.shapes.small,
                ).padding(start = if (expanded) 36.dp else 8.dp, end = if (expanded) 12.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (expanded) {
            Icon(
                item.icon,
                contentDescription = item.label,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )

            Spacer(Modifier.width(12.dp))
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f),
            )

            Checkbox(
                checked = item.visible,
                onCheckedChange = { onToggleVisibility() },
                colors = CheckboxDefaults.colors(checkedColor = colorScheme.primary),
            )
        } else {
            IconButton(
                onClick = onToggleVisibility,
                modifier = Modifier.size(40.dp),
                shapes = IconButtonDefaults.shapes(),
            ) {
                Icon(
                    item.icon,
                    contentDescription = item.label,
                    tint = contentColor,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        Icon(
            MetrolistIcons.Default.DragIndicator,
            contentDescription = stringResource(Res.string.drag_to_reorder_x, item.label),
            tint = if (isDragging || isHandleHovered) colorScheme.primary else colorScheme.onSurfaceVariant,
            modifier =
                modifier
                    .hoverable(handleInteractionSource)
                    .size(24.dp)
                    .padding(start = if (expanded) 4.dp else 0.dp),
        )
    }
}
