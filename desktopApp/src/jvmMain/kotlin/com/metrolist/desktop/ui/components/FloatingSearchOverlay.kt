@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.UiState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.BrowseState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.content.SearchState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.close_search
import com.metrolist.shared.generated.resources.remove_from_search_history
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_for_music
import com.metrolist.shared.generated.resources.search_music
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.acceptsInput
import com.metrolist.shared.ui.navigation.blockInput
import com.metrolist.shared.ui.navigation.rememberNavigationMotion
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.viewmodels.SearchPresentation
import com.metrolist.shared.viewmodels.searchPresentation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

@Composable
fun FloatingSearchOverlay(
    colorScheme: ColorScheme,
    onDismiss: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    var selectedIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(queryState) {
        snapshotFlow { queryState.text.toString() }.drop(1).collect {
            SearchState.onQueryChange(it)
            selectedIndex = -1
        }
    }

    var isVisible by remember { mutableStateOf(false) }
    // Do not dismiss on the initial frame before the entry effect makes the overlay visible.
    var hasBeenVisible by remember { mutableStateOf(false) }
    val motion = rememberNavigationMotion()
    val transition = updateTransition(targetState = isVisible, label = "floatingSearch")

    val items = SearchState.searchHistory
    val remoteSuggestions = SearchState.searchSuggestions
    val presentation = searchPresentation(query, false)

    val historyToDisplay =
        remember(query, items) {
            if (query.isEmpty()) {
                items.take(5)
            } else {
                items
                    .filter {
                        it.contains(query, ignoreCase = true) && it.lowercase() != query.lowercase()
                    }.take(3)
            }
        }
    val querySuggestions =
        remember(query, historyToDisplay, remoteSuggestions) {
            val history: List<Pair<String, Boolean>> = historyToDisplay.map { it to true }
            val remote: List<Pair<String, Boolean>> =
                if (presentation == SearchPresentation.SUGGESTIONS) {
                    remoteSuggestions
                        ?.queries
                        .orEmpty()
                        .filterNot { suggestion -> history.any { it.first.equals(suggestion, ignoreCase = true) } }
                        .map { it to false }
                } else {
                    emptyList()
                }
            (history + remote).take(8)
        }
    val recommendedItems =
        remember(presentation, remoteSuggestions) {
            if (presentation == SearchPresentation.SUGGESTIONS) remoteSuggestions?.recommendedItems.orEmpty() else emptyList()
        }
    val totalItems = querySuggestions.size + recommendedItems.size
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedIndex) {
        if (selectedIndex >= 0) {
            val rowHeightPx = with(density) { 40.dp.roundToPx() }
            val targetPx = (selectedIndex * rowHeightPx).coerceAtMost(scrollState.maxValue)
            scrollState.animateScrollTo(targetPx)
        } else {
            scrollState.animateScrollTo(0)
        }
    }

    LaunchedEffect(Unit) {
        isVisible = true
        hasBeenVisible = true
        SearchState.refreshSearchHistory()
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            delay(100.milliseconds)
            focusRequester.requestFocus()
        }
    }
    LaunchedEffect(transition.currentState, transition.targetState, transition.isRunning) {
        if (hasBeenVisible && !transition.currentState && !transition.targetState && !transition.isRunning) onDismiss()
    }

    val animateDismiss = {
        focusManager.clearFocus()
        isVisible = false
    }

    // Keep the composable mounted during dismissal so external flag changes preserve the exit animation.
    LaunchedEffect(UiState.showFloatingSearch) {
        if (!UiState.showFloatingSearch && isVisible) {
            animateDismiss()
            UiState.showFloatingSearch = true
        }
    }

    val performSubmitSearch = {
        if (selectedIndex in querySuggestions.indices) {
            val (finalQuery, _) = querySuggestions[selectedIndex]
            if (finalQuery.isNotBlank()) {
                if (PlayerState.isExpanded) PlayerState.exitExpanded()
                SearchState.search(finalQuery)
                animateDismiss()
            }
        } else if (selectedIndex >= querySuggestions.size && selectedIndex < totalItems) {
            val itemIndex = selectedIndex - querySuggestions.size
            when (val item = recommendedItems[itemIndex]) {
                is SongItem -> {
                    PlayerState.playTrack(item, listOf(item))
                }

                is BrowseItem -> {
                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                    BrowseState.fetchBrowseData(item.id, item.params, item.title)
                }

                is PodcastItem -> {
                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                    BrowseState.fetchBrowseData(item.id, null, item.title)
                }

                is ArtistItem -> {
                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                    ArtistState.fetchArtistData(item)
                }

                is PlaylistItem -> {
                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                    PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
                }

                is AlbumItem -> {
                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                    PlaylistAlbumState.fetchAlbumData(item.id)
                }

                else -> {}
            }
            animateDismiss()
        } else {
            if (query.isNotBlank()) {
                if (PlayerState.isExpanded) PlayerState.exitExpanded()
                SearchState.search(query)
                animateDismiss()
            }
        }
    }

    val scrimSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val scrimAlpha by transition.animateFloat(transitionSpec = { scrimSpec }, label = "scrimAlpha") { if (it) 0.5f else 0f }

    var queryFocused by remember { mutableStateOf(false) }
    val searchPaneTitle = stringResource(Res.string.search)

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics {
                    paneTitle = searchPaneTitle
                    isTraversalGroup = true
                },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = stringResource(Res.string.close_search),
                        onClick = animateDismiss,
                    ),
        )

        transition.AnimatedVisibility(
            visible = { it },
            enter = motion.popupEnter,
            exit = motion.popupExit,
        ) {
            Surface(
                modifier =
                    Modifier
                        .blockInput(!this.transition.acceptsInput(EnterExitState.Visible))
                        .padding(horizontal = 24.dp)
                        .widthIn(max = 640.dp)
                        .fillMaxWidth()
                        .focusGroup()
                        .onPreviewKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && (queryFocused || event.key == Key.Escape)) {
                                when (event.key) {
                                    Key.Escape -> {
                                        animateDismiss()
                                        true
                                    }

                                    Key.DirectionDown -> {
                                        if (totalItems > 0) {
                                            selectedIndex = (selectedIndex + 1).coerceAtMost(totalItems - 1)
                                        }
                                        true
                                    }

                                    Key.DirectionUp -> {
                                        if (totalItems > 0) {
                                            selectedIndex = (selectedIndex - 1).coerceAtLeast(-1)
                                        }
                                        true
                                    }

                                    Key.DirectionRight -> {
                                        if (selectedIndex in querySuggestions.indices) {
                                            val suggestion = querySuggestions[selectedIndex].first
                                            queryState.setTextAndPlaceCursorAtEnd(suggestion)
                                            selectedIndex = -1
                                            true
                                        } else {
                                            false
                                        }
                                    }

                                    Key.Enter -> {
                                        performSubmitSearch()
                                        true
                                    }

                                    else -> {
                                        false
                                    }
                                }
                            } else {
                                false
                            }
                        },
                shape = RoundedCornerShape(16.dp),
                color = colorScheme.surfaceContainerHigh,
                shadowElevation = 32.dp,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Column(modifier = Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec())) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            MetrolistIcons.Default.Search,
                            null,
                            Modifier.size(24.dp),
                            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                        Spacer(Modifier.width(16.dp))
                        Box(Modifier.weight(1f)) {
                            val searchMusicDesc = stringResource(Res.string.search_music)
                            BasicTextField(
                                state = queryState,
                                textStyle =
                                    MaterialTheme.typography.titleLarge.copy(
                                        color = colorScheme.onSurface,
                                        fontWeight = FontWeight.Normal,
                                    ),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .semantics { contentDescription = searchMusicDesc }
                                        .onFocusChanged { queryFocused = it.isFocused }
                                        .focusRequester(focusRequester),
                                lineLimits = TextFieldLineLimits.SingleLine,
                                cursorBrush = SolidColor(colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                onKeyboardAction = { performSubmitSearch() },
                                decorator = { inner ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) {
                                            Text(
                                                stringResource(Res.string.search_for_music),
                                                style = MaterialTheme.typography.titleLarge,
                                                color = colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                        inner()
                                    }
                                },
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    queryState.setTextAndPlaceCursorAtEnd("")
                                },
                                modifier = Modifier.size(40.dp),
                                shapes = IconButtonDefaults.shapes(),
                            ) {
                                Icon(
                                    MetrolistIcons.Default.Close,
                                    stringResource(Res.string.clear),
                                    Modifier.size(18.dp),
                                    tint = colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }

                    if (querySuggestions.isNotEmpty() ||
                        (presentation == SearchPresentation.SUGGESTIONS && recommendedItems.isNotEmpty())
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.fillMaxWidth(),
                            thickness = 1.dp,
                            color = colorScheme.outlineVariant.copy(alpha = 0.2f),
                        )

                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp)
                                    .verticalScrollWithDrag(scrollState)
                                    .padding(vertical = 6.dp),
                        ) {
                            querySuggestions.forEachIndexed { index, suggestion ->
                                val (entry, isHistory) = suggestion
                                val isSelected = index == selectedIndex
                                val bg = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent

                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 6.dp, vertical = 1.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(bg)
                                            .clickable(role = Role.Button) {
                                                if (PlayerState.isExpanded) PlayerState.exitExpanded()
                                                SearchState.search(entry)
                                                animateDismiss()
                                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        if (isHistory) MetrolistIcons.Default.History else MetrolistIcons.Default.Search,
                                        null,
                                        Modifier.size(18.dp),
                                        tint =
                                            if (isSelected) {
                                                colorScheme.onPrimaryContainer
                                            } else {
                                                colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.6f,
                                                )
                                            },
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Text(
                                        entry,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) colorScheme.onPrimaryContainer else colorScheme.onSurface,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                    )
                                    if (isHistory) {
                                        IconButton(
                                            onClick = { SearchState.removeSearchQuery(entry) },
                                            modifier = Modifier.size(40.dp),
                                            shapes = IconButtonDefaults.shapes(),
                                        ) {
                                            Icon(
                                                MetrolistIcons.Default.Close,
                                                stringResource(Res.string.remove_from_search_history, entry),
                                                Modifier.size(14.dp),
                                                tint = colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            )
                                        }
                                    }
                                }
                            }
                            if (presentation == SearchPresentation.SUGGESTIONS) {
                                recommendedItems.forEachIndexed { itemIndex, item ->
                                    val recIndex = querySuggestions.size + itemIndex
                                    val isSelected = recIndex == selectedIndex
                                    val bg = if (isSelected) colorScheme.primaryContainer.copy(alpha = 0.6f) else Color.Transparent

                                    Box(
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 6.dp, vertical = 1.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(bg),
                                    ) {
                                        YTListItem(item, colorScheme) {
                                            when (item) {
                                                is SongItem -> {
                                                    PlayerState.playTrack(item, listOf(item))
                                                }

                                                is BrowseItem -> {
                                                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                                                    BrowseState.fetchBrowseData(item.id, item.params, item.title)
                                                }

                                                is PodcastItem -> {
                                                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                                                    BrowseState.fetchBrowseData(item.id, null, item.title)
                                                }

                                                is ArtistItem -> {
                                                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                                                    ArtistState.fetchArtistData(item)
                                                }

                                                is PlaylistItem -> {
                                                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                                                    PlaylistAlbumState.fetchPlaylistData(item.id, item.thumbnail)
                                                }

                                                is AlbumItem -> {
                                                    if (PlayerState.isExpanded) PlayerState.exitExpanded()
                                                    PlaylistAlbumState.fetchAlbumData(item.id)
                                                }

                                                else -> {}
                                            }
                                            animateDismiss()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
