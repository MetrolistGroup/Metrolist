/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.remove_recent_search_action
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.viewmodels.SearchViewModel
import org.jetbrains.compose.resources.stringResource

data class SearchScreenText(
    val contentText: SearchContentText,
    val searchPlaceholder: String,
    val back: String,
    val clear: String,
    val microphoneContentDescription: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    uiState: SearchViewModel.SearchUiState,
    text: SearchScreenText,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onBack: () -> Unit,
    onItemClick: (YTItem) -> Unit,
    onVideoItemClick: (YTItem) -> Unit = onItemClick,
    onItemLongClick: (YTItem) -> Unit,
    onSongSave: (SongItem) -> Unit,
    onHistoryItemClick: (String) -> Unit,
    onHistoryItemInsert: (String) -> Unit,
    onClearHistory: () -> Unit,
    onFilterSelected: (SearchFilter?) -> Unit,
    onLoadMore: () -> Unit,
    onRetryLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onRemoveHistoryItem: (String) -> Unit = {},
    onRemoveRecentItem: (YTItem) -> Unit = {},
    onClearRecentItems: () -> Unit = {},
    currentSongId: String? = null,
    isPlaying: Boolean = false,
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onShuffleClick: (YTItem) -> Unit = {},
    onMixClick: (YTItem) -> Unit = {},
    itemContainer: @Composable (YTItem, @Composable () -> Unit) -> Unit = { _, content -> content() },
    onMicrophoneClick: (() -> Unit)? = null,
    focusRequester: FocusRequester = remember { FocusRequester() },
    showSearchBar: Boolean = true,
    searchBarEndPadding: Dp = 0.dp,
    onAlbumPlay: ((AlbumItem) -> Unit)? = null,
) {
    val query = uiState.query
    val isQuerySubmitted = uiState.isQuerySubmitted
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    fun dismissKeyboard() {
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0),
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            contentAlignment = Alignment.TopCenter,
        ) {
            val isWide = maxWidth > 600.dp

            Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (showSearchBar) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(end = searchBarEndPadding),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        Box(modifier = if (isWide) Modifier.widthIn(max = 1400.dp).fillMaxWidth() else Modifier.fillMaxWidth()) {
                            SearchBar(
                                query = query,
                                onQueryChange = onQueryChange,
                                onSearch = onSearch,
                                onClear = { onQueryChange("") },
                                onBack = onBack,
                                onMicrophoneClick = onMicrophoneClick,
                                microphoneContentDescription = text.microphoneContentDescription,
                                isQuerySubmitted = isQuerySubmitted,
                                focusRequester = focusRequester,
                                placeholder = text.searchPlaceholder,
                                backLabel = text.back,
                                clearLabel = text.clear,
                            )
                        }
                    }
                }

                SearchContent(
                    uiState = uiState,
                    text = text.contentText,
                    onItemClick = {
                        dismissKeyboard()
                        onItemClick(it)
                    },
                    onVideoItemClick = {
                        dismissKeyboard()
                        onVideoItemClick(it)
                    },
                    onItemLongClick = onItemLongClick,
                    onSongSave = onSongSave,
                    onHistoryItemClick = {
                        dismissKeyboard()
                        onHistoryItemClick(it)
                    },
                    onHistoryItemInsert = onHistoryItemInsert,
                    onClearHistory = onClearHistory,
                    onFilterSelected = onFilterSelected,
                    onLoadMore = onLoadMore,
                    onRetryLoadMore = onRetryLoadMore,
                    onRemoveHistoryItem = onRemoveHistoryItem,
                    onRemoveRecentItem = onRemoveRecentItem,
                    onClearRecentItems = onClearRecentItems,
                    currentSongId = currentSongId,
                    isPlaying = isPlaying,
                    onArtistClick = onArtistClick,
                    onAlbumClick = onAlbumClick,
                    onShuffleClick = onShuffleClick,
                    onMixClick = onMixClick,
                    itemContainer = itemContainer,
                    contentPadding = contentPadding,
                    modifier = Modifier.weight(1f),
                    onAlbumPlay = onAlbumPlay,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    onMicrophoneClick: (() -> Unit)?,
    microphoneContentDescription: String,
    isQuerySubmitted: Boolean,
    focusRequester: FocusRequester,
    placeholder: String,
    backLabel: String,
    clearLabel: String,
    modifier: Modifier = Modifier,
    history: List<String> = emptyList(),
    onHistoryItemClick: (String) -> Unit = {},
    onRemoveHistoryItem: (String) -> Unit = {},
) {
    val isTv = LocalIsTv.current
    val textFieldState = rememberTextFieldState(initialText = query)
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val currentQuery by rememberUpdatedState(query)
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    var tvKeyboardRequested by remember { mutableStateOf(false) }
    val submitSearch: (String) -> Unit = {
        onSearch(it)
        keyboardController?.hide()
        focusManager.clearFocus()
    }

    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != currentQuery) onQueryChange(text)
        }
    }

    BoxWithConstraints(
        modifier =
            modifier
                .padding(horizontal = 16.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        val isWide = maxWidth > 480.dp

        if (!isWide && !isTv) {
            val searchBarColors =
                SearchBarDefaults.colors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            Surface(
                shape = SearchBarDefaults.inputFieldShape,
                content = {
                    SearchBarDefaults.InputField(
                        textFieldState = textFieldState,
                        searchBarState = searchBarState,
                        onSearch = submitSearch,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                        placeholder = { Text(text = placeholder, maxLines = 1, overflow = TextOverflow.Clip) },
                        leadingIcon = {
                            if (isQuerySubmitted) {
                                IconButton(onClick = onBack, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                                    Icon(
                                        imageVector = MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = backLabel,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = MetrolistIcons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = onClear, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes()) {
                                    Icon(imageVector = MetrolistIcons.Default.Clear, contentDescription = clearLabel)
                                }
                            } else if (onMicrophoneClick != null) {
                                IconButton(
                                    onClick = onMicrophoneClick,
                                    modifier = Modifier.size(48.dp),
                                    shapes = IconButtonDefaults.shapes(),
                                ) {
                                    Icon(
                                        imageVector = MetrolistIcons.Rounded.Mic,
                                        contentDescription = microphoneContentDescription,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                        },
                        colors = searchBarColors.inputFieldColors,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                color = searchBarColors.containerColor,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            )
        } else {
            val barHeight = if (isTv) 48.dp else 40.dp
            val iconSize = 18.dp
            val fontSize = 14.sp

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(barHeight)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (isQuerySubmitted) {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.size(barHeight).tvFocusHighlight(CircleShape, scale = 1f),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = backLabel,
                                modifier = Modifier.size(iconSize),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        Icon(
                            imageVector = MetrolistIcons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(iconSize),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    BasicTextField(
                        state = textFieldState,
                        modifier =
                            Modifier
                                .weight(1f)
                                .height(barHeight)
                                .focusRequester(focusRequester)
                                .tvFocusHighlight(scale = 1f)
                                .onFocusChanged {
                                    isFocused = it.isFocused
                                    if (!it.isFocused) tvKeyboardRequested = false
                                }.onInterceptKeyBeforeSoftKeyboard { event ->
                                    if (isTv && tvKeyboardRequested && event.key == Key.Back) {
                                        if (event.type == KeyEventType.KeyUp) {
                                            keyboardController?.hide()
                                            focusManager.clearFocus()
                                            tvKeyboardRequested = false
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                }.onPreviewKeyEvent { event ->
                                    if (isTv && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                        if (event.type == KeyEventType.KeyUp) {
                                            tvKeyboardRequested = true
                                            keyboardController?.show()
                                        }
                                        true
                                    } else if (isTv) {
                                        val direction =
                                            when (event.key) {
                                                Key.DirectionLeft -> FocusDirection.Left
                                                Key.DirectionRight -> FocusDirection.Right
                                                Key.DirectionUp -> FocusDirection.Up
                                                Key.DirectionDown -> FocusDirection.Down
                                                else -> null
                                            }
                                        if (direction != null) {
                                            if (event.type == KeyEventType.KeyDown) focusManager.moveFocus(direction)
                                            true
                                        } else {
                                            false
                                        }
                                    } else {
                                        false
                                    }
                                },
                        keyboardOptions = KeyboardOptions(showKeyboardOnFocus = !isTv || tvKeyboardRequested),
                        textStyle =
                            MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = fontSize,
                            ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        lineLimits = TextFieldLineLimits.SingleLine,
                        onKeyboardAction = { submitSearch(textFieldState.text.toString()) },
                        decorator = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (query.isEmpty()) {
                                    Text(
                                        text = placeholder,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    )
                                }
                                innerTextField()
                            }
                        },
                    )

                    if (query.isNotEmpty()) {
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier.size(barHeight).tvFocusHighlight(CircleShape, scale = 1f),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.Default.Clear,
                                contentDescription = clearLabel,
                                modifier = Modifier.size(iconSize),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (onMicrophoneClick != null) {
                        IconButton(
                            onClick = onMicrophoneClick,
                            modifier = Modifier.size(barHeight).tvFocusHighlight(CircleShape, scale = 1f),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                imageVector = MetrolistIcons.Rounded.Mic,
                                contentDescription = microphoneContentDescription,
                                modifier = Modifier.size(iconSize),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        DropdownMenu(
            expanded = !isTv && isFocused && history.isNotEmpty() && query.isEmpty(),
            onDismissRequest = { isFocused = false },
            offset = DpOffset(0.dp, 4.dp),
            modifier =
                Modifier
                    .width(maxWidth)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(16.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                history.forEach { historyItem ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onHistoryItemClick(historyItem)
                                    isFocused = false
                                }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            MetrolistIcons.Outlined.History,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = historyItem,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(
                            onClick = { onRemoveHistoryItem(historyItem) },
                            modifier = Modifier.size(48.dp),
                            shapes = IconButtonDefaults.shapes(),
                        ) {
                            Icon(
                                MetrolistIcons.Default.Close,
                                contentDescription = stringResource(Res.string.remove_recent_search_action),
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }
        }
    }
}
