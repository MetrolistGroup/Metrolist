/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.components.SwipeBackLayout
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.components.singleLineMarquee
import com.metrolist.shared.ui.equalizer.MetroEqScreen
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.navigation.PredictiveNavigationContent
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.desktopArrowScroll
import com.metrolist.shared.ui.utils.segmentedGroupShape
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun SharedSettingsScreen(
    host: SharedSettingsHost,
    modifier: Modifier = Modifier,
) {
    val settingsHost =
        host.copy(
            availablePages = host.availablePages + SettingsPageId.EQUALIZER,
            pageContent = { page, onBack, onNavigate ->
                if (page == SettingsPageId.EQUALIZER) {
                    MetroEqScreen(
                        rememberProfilePicker = host.rememberMetroEqProfilePicker,
                        isAudioProcessingAvailable = host.isAudioProcessingAvailable,
                    )
                } else if (page == SettingsPageId.LYRICS_AI_SETUP) {
                    AiTranslationSetup(host.hostChoices[SettingsHostChoice.LYRICS_TARGET_LANGUAGE].orEmpty(), onBack)
                } else {
                    host.pageContent(page, onBack, onNavigate)
                }
            },
        )
    SharedSettingsRegistryScreen(settingsHost, modifier) { settingsRevision ->
        rememberMetrolistSettingsRegistry(settingsHost, settingsRevision)
    }
}

val LocalSettingsPageBottomPadding = compositionLocalOf { 0.dp }

val LocalSettingsPageHasTopBar = compositionLocalOf { false }

@Composable
fun rememberSettingsPageScrollBehavior(compactTopBar: Boolean = false): TopAppBarScrollBehavior? =
    if (LocalSettingsPageHasTopBar.current || compactTopBar) {
        null
    } else {
        TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    }

@Composable
internal fun SharedSettingsRegistryScreen(
    host: SharedSettingsHost,
    modifier: Modifier = Modifier,
    registryFactory: @Composable (Int) -> SettingsUiRegistry,
) {
    var settingsRevision by remember { mutableIntStateOf(0) }
    var pageStack by rememberSaveable {
        mutableStateOf(host.initialPage?.let { listOf(it.name) }.orEmpty())
    }
    var highlightedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val registry = registryFactory(settingsRevision)
    LaunchedEffect(host.initialPage, registry) {
        val initialPage = host.initialPage
        if (initialPage != null && pageStack == listOf(initialPage.name)) {
            pageStack = registry.settingsPageStack(initialPage)
        }
    }
    val selectedPageName = pageStack.lastOrNull()
    val selectedPageId = selectedPageName?.let { runCatching { SettingsPageId.valueOf(it) }.getOrNull() }
    SideEffect { host.onPageChanged(selectedPageId) }
    val navigationMotion = rememberNavigationMotion()
    val compactStateHolder = rememberSaveableStateHolder()
    val homeListState = rememberLazyListState()
    val pageListStates = registry.pages.associate { page -> key(page.id) { page.id to rememberLazyListState() } }

    fun openPage(
        pageId: SettingsPageId,
        parentPageId: SettingsPageId? = null,
        highlightedId: String? = null,
    ) {
        if (registry[pageId] == null) return
        val nextStack = registry.settingsPageStackOnNavigate(pageStack, pageId, parentPageId, host.externalPages)
        if (nextStack == null) {
            host.onOpenPage(pageId)
        } else {
            highlightedItemId = highlightedId
            pageStack = nextStack
        }
    }

    fun closePage() {
        highlightedItemId = null
        pageStack = pageStack.dropLast(1)
    }

    var containerWidth by remember { mutableIntStateOf(0) }
    val expanded =
        !host.singlePane && with(LocalDensity.current) { containerWidth.toDp() >= 840.dp }
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onSizeChanged { containerWidth = it.width },
    ) {
        val previousPageId = pageStack.dropLast(1).lastOrNull()?.let { runCatching { SettingsPageId.valueOf(it) }.getOrNull() }
        if (expanded) {
            val detailPageId = selectedPageId ?: registry.pages.firstOrNull { !it.custom }?.id
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                SettingsNavigationPane(
                    registry = registry,
                    selectedPageId = registry.pages.firstOrNull { it.id.name == pageStack.firstOrNull() }?.id ?: detailPageId,
                    bottomContentPadding = host.bottomContentPadding,
                    onPageSelected = { openPage(it) },
                    onSearchResultSelected = { pageId, itemId ->
                        openPage(pageId, highlightedId = itemId)
                    },
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                )
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 12.dp, bottom = 12.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(80.dp).padding(start = 32.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (canNavigateBackFromSettings(pageStack, expanded = true)) {
                            IconButton(onClick = ::closePage, shapes = IconButtonDefaults.shapes()) {
                                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                            }
                        }
                        AnimatedContent(
                            targetState = detailPageId,
                            transitionSpec = { navigationMotion.transition() },
                            contentAlignment = Alignment.CenterStart,
                            label = "desktopSettingsTitle",
                            modifier = Modifier.weight(1f),
                        ) { pageId ->
                            Text(
                                text = pageId?.let(registry::get)?.title.orEmpty(),
                                style = MaterialTheme.typography.headlineMediumEmphasized,
                                maxLines = 1,
                                modifier = Modifier.singleLineMarquee(),
                            )
                        }
                        IconButton(onClick = host.onClose, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close))
                        }
                    }
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        PredictiveNavigationContent(
                            targetState = detailPageId,
                            previousState = previousPageId ?: registry.pages.firstOrNull { !it.custom }?.id,
                            backEnabled = canNavigateBackFromSettings(pageStack, expanded = true),
                            onBack = ::closePage,
                            modifier = Modifier.fillMaxSize(),
                        ) { pageId ->
                            Box(Modifier.fillMaxSize()) {
                                pageId?.let(registry::get)?.let { page ->
                                    SettingsDetailPane(
                                        page = page,
                                        listState = pageListStates.getValue(page.id),
                                        host = host,
                                        onBack = ::closePage,
                                        highlightedItemId = highlightedItemId,
                                        onNavigate = { openPage(it, parentPageId = page.id) },
                                        onSettingValueChanged = { settingsRevision++ },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val compactContent: @Composable (SettingsPageId?, String?) -> Unit = { pageId, itemId ->
                if (pageId == null) {
                    CompactSettingsHome(
                        registry = registry,
                        listState = homeListState,
                        host = host,
                        onPageSelected = { openPage(it) },
                        onSearchResultSelected = { resultPageId, resultItemId ->
                            openPage(resultPageId, highlightedId = resultItemId)
                        },
                    )
                } else {
                    registry[pageId]?.let { page ->
                        CompactSettingsDetail(
                            page = page,
                            listState = pageListStates.getValue(page.id),
                            bottomContentPadding = host.bottomContentPadding,
                            pageContent = host.pageContent,
                            lyricsPreview = host.lyricsPreview,
                            highlightedItemId = itemId,
                            onBack = ::closePage,
                            onNavigate = { openPage(it, parentPageId = page.id) },
                            onSettingValueChanged = { settingsRevision++ },
                        )
                    }
                }
            }
            val previousContent: @Composable () -> Unit = {
                compactContent(previousPageId, null)
            }
            PredictiveNavigationContent(
                targetState = selectedPageId,
                previousState = previousPageId,
                backEnabled = canNavigateBackFromSettings(pageStack, expanded = false),
                onBack = ::closePage,
                modifier = Modifier.fillMaxSize(),
            ) { pageId ->
                compactStateHolder.SaveableStateProvider(pageId?.name ?: "settingsRoot") {
                    if (pageId == null) {
                        compactContent(null, highlightedItemId)
                    } else {
                        SwipeBackLayout(
                            enabled = host.platform == SettingsPlatform.IOS,
                            onBack = ::closePage,
                            previousContent = previousContent,
                        ) {
                            compactContent(pageId, highlightedItemId)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactSettingsHome(
    registry: SettingsUiRegistry,
    listState: LazyListState,
    host: SharedSettingsHost,
    onPageSelected: (SettingsPageId) -> Unit,
    onSearchResultSelected: (SettingsPageId, String?) -> Unit,
) {
    val isTv = LocalIsTv.current
    val contentFocusRequester = remember { FocusRequester() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = {
                    when {
                        host.title != null -> {
                            Text(host.title, fontWeight = MaterialTheme.typography.titleLargeEmphasized.fontWeight)
                        }

                        host.platform != SettingsPlatform.DESKTOP -> {
                            Text(
                                stringResource(Res.string.settings),
                                fontWeight = MaterialTheme.typography.titleLargeEmphasized.fontWeight,
                            )
                        }

                        else -> {}
                    }
                },
                navigationIcon = {
                    if (host.platform != SettingsPlatform.DESKTOP) {
                        IconButton(
                            onClick = host.onClose,
                            modifier =
                                Modifier.focusProperties {
                                    if (isTv) down = contentFocusRequester
                                },
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                        }
                    }
                },
                actions = {
                    if (host.platform == SettingsPlatform.DESKTOP) {
                        IconButton(onClick = host.onClose, shapes = IconButtonDefaults.shapes()) {
                            Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close))
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(if (isTv) Modifier.focusRequester(contentFocusRequester).focusGroup() else Modifier) {
            SettingsHomeList(
                registry = registry,
                listState = listState,
                customPageIcons = host.customPageIcons,
                onPageSelected = onPageSelected,
                onSearchResultSelected = onSearchResultSelected,
                contentPadding =
                    PaddingValues(
                        top = padding.calculateTopPadding() + 12.dp,
                        bottom = 24.dp + host.bottomContentPadding,
                    ),
            )
        }
    }
}

@Composable
internal fun SettingsPageScreen(
    page: SettingsPageRegistration,
    onNavigateBack: () -> Unit,
    bottomContentPadding: Dp = 0.dp,
) {
    CompactSettingsDetail(
        page = page,
        listState = rememberLazyListState(),
        bottomContentPadding = bottomContentPadding,
        pageContent = { _, _, _ -> },
        highlightedItemId = null,
        onBack = onNavigateBack,
        onNavigate = {},
        onSettingValueChanged = {},
    )
}

@Composable
private fun CompactSettingsDetail(
    page: SettingsPageRegistration,
    listState: LazyListState,
    bottomContentPadding: Dp,
    pageContent: @Composable (SettingsPageId, () -> Unit, (SettingsPageId) -> Unit) -> Unit,
    lyricsPreview: @Composable (Modifier) -> Unit = { LyricsSettingsPreview(it) },
    highlightedItemId: String?,
    onBack: () -> Unit,
    onNavigate: (SettingsPageId) -> Unit,
    onSettingValueChanged: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val contentFocusRequester = remember { FocusRequester() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(page.title, fontWeight = MaterialTheme.typography.titleLargeEmphasized.fontWeight) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier =
                            Modifier.focusProperties {
                                if (isTv) down = contentFocusRequester
                            },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, stringResource(Res.string.back))
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(if (isTv) Modifier.focusRequester(contentFocusRequester).focusGroup() else Modifier) {
            if (page.custom) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(top = padding.calculateTopPadding()),
                ) {
                    CompositionLocalProvider(
                        LocalSettingsPageBottomPadding provides bottomContentPadding,
                        LocalSettingsPageHasTopBar provides true,
                    ) {
                        pageContent(page.id, onBack, onNavigate)
                    }
                }
            } else {
                SettingsPageList(
                    page = page,
                    listState = listState,
                    lyricsPreview = lyricsPreview,
                    highlightedItemId = highlightedItemId,
                    onNavigate = onNavigate,
                    onSettingValueChanged = onSettingValueChanged,
                    contentPadding =
                        PaddingValues(
                            top = padding.calculateTopPadding() + 12.dp,
                            bottom = 24.dp + bottomContentPadding,
                        ),
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigationPane(
    registry: SettingsUiRegistry,
    selectedPageId: SettingsPageId?,
    bottomContentPadding: Dp,
    onPageSelected: (SettingsPageId) -> Unit,
    onSearchResultSelected: (SettingsPageId, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        var query by rememberSaveable { mutableStateOf("") }
        SettingsSearchField(query = query, onQueryChange = { query = it })
        Spacer(Modifier.height(16.dp))
        if (query.isBlank()) {
            val navigationListState = rememberLazyListState()
            DragScrollableColumn(
                state = navigationListState,
                modifier = Modifier.fillMaxSize().desktopArrowScroll(navigationListState),
                contentPadding = PaddingValues(bottom = bottomContentPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                registry.rootGroups.filter { it.pages.isNotEmpty() }.forEach { group ->
                    item(group.title) {
                        Text(
                            text = group.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                        )
                    }
                    items(group.pages.size, key = { group.pages[it].name }) { index ->
                        val pageId = group.pages[index]
                        registry[pageId]?.let { page ->
                            NavigationPaneItem(
                                page = page,
                                selected = pageId == selectedPageId,
                                first = index == 0,
                                last = index == group.pages.lastIndex,
                                onClick = { onPageSelected(pageId) },
                            )
                        }
                    }
                }
            }
        } else {
            SettingsSearchResults(
                registry = registry,
                query = query,
                onResultSelected = onSearchResultSelected,
                compact = true,
                contentPadding = PaddingValues(bottom = bottomContentPadding),
            )
        }
    }
}

@Composable
private fun SettingsHomeList(
    registry: SettingsUiRegistry,
    listState: LazyListState,
    customPageIcons: Map<SettingsPageId, @Composable (Color) -> Unit>,
    onPageSelected: (SettingsPageId) -> Unit,
    onSearchResultSelected: (SettingsPageId, String?) -> Unit,
    contentPadding: PaddingValues,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchResults = rememberSettingsSearchResults(registry, query)
    DragScrollableColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().desktopArrowScroll(listState),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(if (query.isBlank()) 16.dp else 4.dp),
    ) {
        item("search") {
            SettingsSearchField(
                query = query,
                onQueryChange = { query = it },
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = if (query.isBlank()) 0.dp else 12.dp),
            )
        }
        if (query.isBlank()) {
            registry.rootGroups.filter { it.pages.isNotEmpty() }.forEach { group ->
                item(group.title) {
                    SettingsRootGroup(group, registry, customPageIcons, onPageSelected)
                }
            }
        } else if (searchResults.isNotEmpty()) {
            items(searchResults.size, key = { "${searchResults[it].page.id}:${searchResults[it].item?.id}" }) { index ->
                Box(Modifier.padding(horizontal = 16.dp)) {
                    SearchResultRow(searchResults[index], query, index == 0, index == searchResults.lastIndex, onSearchResultSelected)
                }
            }
        } else {
            item("results") {
                SettingsSearchResults(
                    registry = registry,
                    query = query,
                    onResultSelected = onSearchResultSelected,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun SettingsDetailPane(
    page: SettingsPageRegistration,
    listState: LazyListState,
    host: SharedSettingsHost,
    onBack: () -> Unit,
    highlightedItemId: String?,
    onNavigate: (SettingsPageId) -> Unit,
    onSettingValueChanged: () -> Unit,
) {
    if (page.custom) {
        Box(Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalSettingsPageBottomPadding provides host.bottomContentPadding,
                LocalSettingsPageHasTopBar provides true,
            ) {
                host.pageContent(page.id, onBack, onNavigate)
            }
        }
    } else {
        SettingsPageList(
            page = page,
            listState = listState,
            lyricsPreview = host.lyricsPreview,
            highlightedItemId = highlightedItemId,
            onNavigate = onNavigate,
            onSettingValueChanged = onSettingValueChanged,
            contentPadding = PaddingValues(top = 28.dp, bottom = 28.dp + host.bottomContentPadding),
        )
    }
}

@Composable
private fun SettingsPageList(
    page: SettingsPageRegistration,
    lyricsPreview: @Composable (Modifier) -> Unit,
    listState: LazyListState,
    highlightedItemId: String?,
    onNavigate: (SettingsPageId) -> Unit,
    onSettingValueChanged: () -> Unit,
    contentPadding: PaddingValues,
) {
    var dialogItem by remember { mutableStateOf<SettingsItemRegistration?>(null) }
    val groups = page.groups.filter { it.items.isNotEmpty() }
    val mainSwitch =
        groups
            .firstOrNull()
            ?.items
            ?.singleOrNull()
            ?.takeIf { it.control == SettingsControl.MAIN_SWITCH }
    val highlightedGroupIndex =
        groups.indexOfFirst { group -> group.items.any { it.id == highlightedItemId } }.let { index ->
            when {
                index < 0 -> {
                    -1
                }

                mainSwitch?.checked == false -> {
                    0
                }

                else -> {
                    groups.take(index).sumOf { it.items.size + 1 } + 1 +
                        groups[index].items.indexOfFirst { it.id == highlightedItemId } +
                        if (mainSwitch != null && index > 0) 1 else 0
                }
            }
        }
    LaunchedEffect(page.id, highlightedItemId, highlightedGroupIndex) {
        if (highlightedGroupIndex >= 0) listState.scrollToItem(highlightedGroupIndex)
    }
    val hasPreview = page.id == SettingsPageId.LYRICS
    val fixedPreview = hasPreview && !LocalIsTv.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(top = if (fixedPreview) contentPadding.calculateTopPadding() else 0.dp)) {
        val previewHeight = minOf(300.dp, maxHeight * 0.4f)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (fixedPreview) 16.dp else 0.dp)) {
            if (fixedPreview) {
                lyricsPreview(Modifier.fillMaxWidth().height(previewHeight).padding(horizontal = 16.dp))
            }
            DragScrollableColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().desktopArrowScroll(listState),
                contentPadding =
                    if (fixedPreview) {
                        PaddingValues(
                            bottom = contentPadding.calculateBottomPadding(),
                        )
                    } else {
                        contentPadding
                    },
            ) {
                groups.forEachIndexed { groupIndex, group ->
                    item("heading_${group.id}", contentType = "heading") {
                        AnimatedVisibility(
                            visible = mainSwitch?.checked != false || groupIndex == 0,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Box(
                                Modifier.padding(
                                    top =
                                        if (group.title.isBlank()) {
                                            0.dp
                                        } else if (groupIndex == 0) {
                                            8.dp
                                        } else {
                                            24.dp
                                        },
                                ),
                            ) {
                                SettingsItemGroup(group.copy(items = emptyList()), highlightedItemId, onSettingValueChanged, {})
                            }
                        }
                    }
                    items(
                        group.items.size,
                        key = { "${group.id}:${group.items[it].id}" },
                        contentType = { group.items[it].control },
                    ) { index ->
                        val item = group.items[index]
                        AnimatedVisibility(
                            visible = mainSwitch?.checked != false || groupIndex == 0,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            Box(
                                Modifier
                                    .padding(horizontal = 16.dp)
                                    .padding(bottom = if (index == group.items.lastIndex) 0.dp else 4.dp),
                            ) {
                                SettingsItemRow(
                                    item =
                                        if (group.enabled &&
                                            (mainSwitch?.checked != false || groupIndex == 0)
                                        ) {
                                            item
                                        } else {
                                            item.copy(enabled = false)
                                        },
                                    first = index == 0,
                                    last = index == group.items.lastIndex,
                                    highlighted = item.id == highlightedItemId,
                                    onSettingValueChanged = onSettingValueChanged,
                                    onClick = {
                                        when (item.control) {
                                            SettingsControl.SWITCH, SettingsControl.MAIN_SWITCH -> {
                                                item.onCheckedChange?.invoke(item.checked != true)
                                                onSettingValueChanged()
                                            }

                                            SettingsControl.SLIDER, SettingsControl.TEXT -> {
                                                dialogItem = item
                                            }

                                            SettingsControl.CHOICE -> {
                                            }

                                            SettingsControl.NAVIGATION -> {
                                                item.destination?.let(onNavigate)
                                            }

                                            SettingsControl.ACTION -> {
                                                if (item.confirmationMessage == null) {
                                                    item.onClick?.invoke()
                                                    onSettingValueChanged()
                                                } else {
                                                    dialogItem = item
                                                }
                                            }

                                            SettingsControl.CUSTOM -> {
                                            }
                                        }
                                    },
                                )
                            }
                        }
                    }
                    if (groupIndex == 0 && mainSwitch != null) {
                        item("integration_description") {
                            AnimatedVisibility(
                                visible = mainSwitch.checked == false,
                                enter = expandVertically() + fadeIn(),
                                exit = shrinkVertically() + fadeOut(),
                            ) {
                                Text(
                                    text = mainSwitch.description.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(16.dp),
                                )
                            }
                        }
                    }
                }
                if (hasPreview && !fixedPreview) {
                    item("lyrics_preview") {
                        lyricsPreview(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 16.dp)
                                .height(previewHeight)
                                .padding(horizontal = 16.dp),
                        )
                    }
                }
            }
        }
    }
    val activeDialogItem =
        dialogItem?.let { dialog ->
            groups
                .firstOrNull { group -> group.enabled && group.items.any { it.id == dialog.id && it.enabled } }
                ?.items
                ?.firstOrNull { it.id == dialog.id }
                ?.takeUnless { mainSwitch?.checked == false }
        }
    LaunchedEffect(activeDialogItem) { if (activeDialogItem == null) dialogItem = null }
    activeDialogItem?.let { item ->
        when (item.control) {
            SettingsControl.SLIDER -> {
                SettingsSliderDialog(
                    item,
                    onDismiss = { dialogItem = null },
                    onSettingValueChanged = onSettingValueChanged,
                )
            }

            SettingsControl.TEXT -> {
                SettingsTextDialog(
                    item,
                    onDismiss = { dialogItem = null },
                    onSettingValueChanged = onSettingValueChanged,
                )
            }

            SettingsControl.ACTION -> {
                SettingsConfirmationDialog(
                    item,
                    onDismiss = { dialogItem = null },
                    onSettingValueChanged = onSettingValueChanged,
                )
            }

            else -> {
            }
        }
    }
}

@Composable
private fun SettingsRootGroup(
    group: SettingsRootGroupRegistration,
    registry: SettingsUiRegistry,
    customPageIcons: Map<SettingsPageId, @Composable (Color) -> Unit>,
    onPageSelected: (SettingsPageId) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            group.pages.forEachIndexed { index, pageId ->
                registry[pageId]?.let { page ->
                    SettingsNavigationRow(
                        title = page.title,
                        subtitle = page.subtitle,
                        icon = page.icon,
                        customIcon = customPageIcons[pageId],
                        first = index == 0,
                        last = index == group.pages.lastIndex,
                        onClick = { onPageSelected(pageId) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    icon: ImageVector,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    customIcon: (@Composable (Color) -> Unit)? = null,
    iconSize: Dp = 24.dp,
    iconTint: Color? = null,
    titleFontSize: TextUnit = TextUnit.Unspecified,
    trailingContent: (@Composable () -> Unit)? = {
        Icon(MetrolistIcons.Outlined.ChevronRight, null, tint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant)
    },
    focusModifier: @Composable (Shape) -> Modifier = { Modifier },
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = segmentedGroupShape(first, last, pressed)
    val containerColor by animateColorAsState(
        if (pressed) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer,
        label = "settingsRootRow",
    )
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .then(focusModifier(shape))
                .clip(shape)
                .background(containerColor)
                .focusOutline(shape)
                .clickable(interactionSource = interactionSource, indication = ripple(), onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val resolvedIconTint = iconTint ?: MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = Modifier.size(iconSize + 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (customIcon != null) {
                customIcon(resolvedIconTint)
            } else {
                Icon(icon, null, tint = resolvedIconTint, modifier = Modifier.size(iconSize))
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = titleFontSize,
            )
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailingContent?.let {
            Spacer(Modifier.width(12.dp))
            it()
        }
    }
}

@Composable
internal fun SettingsItemGroup(
    group: SettingsGroupRegistration,
    highlightedItemId: String?,
    onSettingValueChanged: () -> Unit,
    onItemClick: (SettingsItemRegistration) -> Unit,
    horizontalPadding: Dp = 16.dp,
    showHeading: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth().alpha(if (group.enabled) 1f else 0.6f)) {
        if (showHeading && group.title.isNotBlank()) {
            Column(
                modifier = Modifier.padding(start = horizontalPadding, end = horizontalPadding * 2, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = group.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { heading() },
                )
                group.description?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = horizontalPadding),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            group.items.forEachIndexed { index, item ->
                SettingsItemRow(
                    item = if (group.enabled) item else item.copy(enabled = false),
                    first = index == 0,
                    last = index == group.items.lastIndex,
                    highlighted = item.id == highlightedItemId,
                    onSettingValueChanged = onSettingValueChanged,
                    onClick = { onItemClick(item) },
                )
            }
        }
        group.footer?.let { footer ->
            Text(
                footer,
                modifier = Modifier.padding(horizontal = horizontalPadding * 2, vertical = 12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsItemRow(
    item: SettingsItemRegistration,
    first: Boolean,
    last: Boolean,
    highlighted: Boolean,
    onSettingValueChanged: () -> Unit,
    onClick: () -> Unit,
) {
    val haptics = rememberMetrolistHaptics()
    if (item.control == SettingsControl.MAIN_SWITCH) {
        SettingsMainSwitch(
            title = item.title,
            checked = item.checked == true,
            enabled = item.enabled,
            onCheckedChange = {
                item.onCheckedChange?.invoke(it)
                onSettingValueChanged()
            },
        )
        return
    }
    if (item.control == SettingsControl.CUSTOM) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = connectedShape(first, last, outer = 20.dp, inner = 5.dp),
            color = MaterialTheme.colorScheme.settingsRowContainerColor(),
        ) {
            Box(Modifier.alpha(if (item.enabled) 1f else 0.38f)) {
                item.customContent?.invoke(onSettingValueChanged)
            }
        }
        return
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val shape = segmentedGroupShape(first, last, pressed)
    val background by animateColorAsState(
        targetValue =
            if (highlighted) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.settingsRowContainerColor(pressed),
        label = "settingsItemRow",
    )
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            delay(50.milliseconds)
            bringIntoViewRequester.bringIntoView()
        }
    }
    val interactive = item.enabled && item.control != SettingsControl.CUSTOM
    var choiceMenuExpanded by remember(item.id) { mutableStateOf(false) }
    var choiceMenuOffset by remember(item.id) { mutableStateOf(IntOffset.Zero) }
    Box(modifier = Modifier.fillMaxWidth().trackPressPosition { choiceMenuOffset = it.round() }) {
        Row(
            modifier =
                Modifier
                    .bringIntoViewRequester(bringIntoViewRequester)
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .clip(shape)
                    .background(background)
                    .focusOutline(shape)
                    .then(
                        if (item.control == SettingsControl.SWITCH && item.checked != null) {
                            Modifier.toggleable(
                                value = item.checked,
                                enabled = interactive,
                                role = Role.Switch,
                                interactionSource = interactionSource,
                                indication = ripple(),
                                onValueChange = {
                                    haptics.toggle(it)
                                    item.onCheckedChange?.invoke(it)
                                    onSettingValueChanged()
                                },
                            )
                        } else {
                            Modifier.clickable(
                                enabled = interactive,
                                interactionSource = interactionSource,
                                indication = ripple(),
                                onClick =
                                    if (item.control == SettingsControl.CHOICE) {
                                        { choiceMenuExpanded = true }
                                    } else {
                                        onClick
                                    },
                            )
                        },
                    ).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color =
                            if (item.enabled) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            },
                        modifier = Modifier.weight(1f),
                    )
                }
                (item.annotatedDescription ?: item.description?.let(::AnnotatedString))?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.enabled) 1f else 0.38f),
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            when (item.control) {
                SettingsControl.SWITCH, SettingsControl.MAIN_SWITCH -> {
                    ExpressiveSwitch(checked = item.checked == true, onCheckedChange = null, enabled = item.enabled)
                }

                SettingsControl.CHOICE, SettingsControl.SLIDER, SettingsControl.TEXT -> {
                    item.valueLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color =
                                if (item.enabled) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                },
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 128.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        imageVector =
                            if (item.control == SettingsControl.CHOICE) {
                                if (choiceMenuExpanded) {
                                    MetrolistIcons.Outlined.KeyboardArrowUp
                                } else {
                                    MetrolistIcons.Outlined.KeyboardArrowDown
                                }
                            } else {
                                MetrolistIcons.Outlined.ChevronRight
                            },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (item.enabled) 1f else 0.38f),
                    )
                }

                SettingsControl.NAVIGATION -> {
                    item.valueLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 128.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(MetrolistIcons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                SettingsControl.ACTION -> {
                    item.valueLabel?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            modifier = Modifier.widthIn(max = 128.dp),
                        )
                    }
                }

                SettingsControl.CUSTOM -> {}
            }
        }
        if (item.control == SettingsControl.CHOICE) {
            ExpressiveSelectionMenu(
                expanded = choiceMenuExpanded,
                onDismissRequest = { choiceMenuExpanded = false },
                anchorOffset = choiceMenuOffset,
                options = item.choices,
                selectedOption = item.choices.find { it.id == item.selectedChoiceId },
                optionLabel = { it.label },
                optionIcon = { it.preview?.invoke() },
                onOptionSelected = { choice ->
                    item.onChoiceSelected?.invoke(choice.id)
                    onSettingValueChanged()
                },
            )
        }
    }
}

@Composable
private fun NavigationPaneItem(
    page: SettingsPageRegistration,
    selected: Boolean,
    first: Boolean,
    last: Boolean,
    onClick: () -> Unit,
) {
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        label = "settingsNavigationItem",
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .clip(connectedShape(first, last, outer = 20.dp, inner = 5.dp))
                .background(background)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = page.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                page.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            page.subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal fun connectedShape(
    first: Boolean,
    last: Boolean,
    outer: Dp,
    inner: Dp,
) = RoundedCornerShape(
    topStart = if (first) outer else inner,
    topEnd = if (first) outer else inner,
    bottomStart = if (last) outer else inner,
    bottomEnd = if (last) outer else inner,
)
