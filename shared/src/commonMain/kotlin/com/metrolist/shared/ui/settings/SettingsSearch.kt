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

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.focusOutline
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.DragScrollableColumn
import com.metrolist.shared.ui.utils.desktopArrowScroll
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SettingsSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textFieldState = rememberTextFieldState(initialText = query)
    val latestValue by rememberUpdatedState(query)
    val latestOnValueChange by rememberUpdatedState(onQueryChange)
    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect { text ->
            if (text != latestValue) latestOnValueChange(text)
        }
    }

    val searchLabel = stringResource(Res.string.settings_search_placeholder)
    val focusManager = LocalFocusManager.current
    val isTv = LocalIsTv.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var tvKeyboardRequested by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Surface(
        modifier =
            modifier
                .fillMaxWidth()
                .height(56.dp)
                .focusOutline(CircleShape, focused = isFocused),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(MetrolistIcons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    Text(
                        searchLabel,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    state = textFieldState,
                    lineLimits = TextFieldLineLimits.SingleLine,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    interactionSource = interactionSource,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = !isTv || tvKeyboardRequested),
                    onKeyboardAction = { focusManager.clearFocus() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { if (!it.isFocused) tvKeyboardRequested = false }
                            .onPreviewKeyEvent { event ->
                                if (isTv && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                                    if (event.type == KeyEventType.KeyUp) {
                                        tvKeyboardRequested = true
                                        keyboardController?.show()
                                    }
                                    return@onPreviewKeyEvent true
                                }
                                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                when (event.key) {
                                    Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                                    Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                                    else -> false
                                }
                            }.semantics { contentDescription = searchLabel },
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }, modifier = Modifier.size(36.dp), shapes = IconButtonDefaults.shapes()) {
                    Icon(MetrolistIcons.Default.Close, stringResource(Res.string.close), modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

internal data class SettingsSearchResult(
    val page: SettingsPageRegistration,
    val item: SettingsItemRegistration?,
    val context: String? = null,
)

@Composable
internal fun SettingsSearchResults(
    registry: SettingsUiRegistry,
    query: String,
    onResultSelected: (SettingsPageId, String?) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val results = rememberSettingsSearchResults(registry, query)
    if (results.isEmpty()) {
        Column(
            modifier = modifier.fillMaxWidth().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(MetrolistIcons.Outlined.Search, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(Res.string.settings_search_no_results), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(Res.string.settings_search_no_results_desc, query),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    if (compact) {
        val listState = rememberLazyListState()
        DragScrollableColumn(
            state = listState,
            modifier = modifier.fillMaxSize().desktopArrowScroll(listState),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(results, key = { _, result -> "${result.page.id}:${result.item?.id}" }) { index, result ->
                SearchResultRow(
                    result = result,
                    query = query,
                    first = index == 0,
                    last = index == results.lastIndex,
                    onResultSelected = onResultSelected,
                )
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            results.forEachIndexed { index, result ->
                SearchResultRow(
                    result = result,
                    query = query,
                    first = index == 0,
                    last = index == results.lastIndex,
                    onResultSelected = onResultSelected,
                )
            }
        }
    }
}

@Composable
internal fun SearchResultRow(
    result: SettingsSearchResult,
    query: String,
    first: Boolean,
    last: Boolean,
    onResultSelected: (SettingsPageId, String?) -> Unit,
) {
    val title = result.item?.title ?: result.page.title
    val supportingText = result.context ?: if (result.item != null) result.page.title else result.page.subtitle
    Surface(
        onClick = { onResultSelected(result.page.id, result.item?.id) },
        modifier = Modifier.fillMaxWidth(),
        shape = connectedShape(first, last, outer = 20.dp, inner = 5.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = result.page.icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    highlightSearchMatches(title, query),
                    style = MaterialTheme.typography.bodyLarge,
                )
                supportingText?.let {
                    Text(
                        highlightSearchMatches(it, query),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (result.item != null && supportingText != result.page.title) {
                    Text(
                        highlightSearchMatches(result.page.title, query),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(MetrolistIcons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun highlightSearchMatches(
    text: String,
    query: String,
): AnnotatedString {
    val match = query.trim()
    if (match.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var currentIndex = 0
        while (currentIndex < text.length) {
            val matchIndex = text.indexOf(match, startIndex = currentIndex, ignoreCase = true)
            if (matchIndex < 0) {
                append(text.substring(currentIndex))
                break
            }
            append(text.substring(currentIndex, matchIndex))
            withStyle(
                SpanStyle(
                    color = Color(0xFF211A00),
                    background = Color(0xFFFFD54F),
                    fontWeight = FontWeight.SemiBold,
                ),
            ) {
                append(text.substring(matchIndex, matchIndex + match.length))
            }
            currentIndex = matchIndex + match.length
        }
    }
}

@Composable
internal fun rememberSettingsSearchResults(
    registry: SettingsUiRegistry,
    query: String,
): List<SettingsSearchResult> {
    val normalized = query.trim().lowercase()
    val listenTogetherEnabled = Settings.shared.listenTogetherEnabled
    return remember(registry, normalized, listenTogetherEnabled) {
        settingsSearchResults(registry, normalized, listenTogetherEnabled)
    }
}

internal fun settingsSearchResults(
    registry: SettingsUiRegistry,
    normalized: String,
    listenTogetherEnabled: Boolean,
): List<SettingsSearchResult> =
    if (normalized.isBlank()) {
        emptyList()
    } else {
        registry.pages
            .filter { it.id != SettingsPageId.LISTEN_TOGETHER || listenTogetherEnabled }
            .flatMap { page ->
                buildList {
                    val matchingTerm = page.searchTerms.firstOrNull { it.contains(normalized, ignoreCase = true) }
                    if (page.title.lowercase().contains(normalized) ||
                        page.subtitle
                            .orEmpty()
                            .lowercase()
                            .contains(normalized) ||
                        matchingTerm != null
                    ) {
                        add(SettingsSearchResult(page, null, matchingTerm))
                    }
                    if (!page.custom) {
                        page.groups.forEach { group ->
                            group.items.forEach { item ->
                                if (
                                    page.id == SettingsPageId.HIDDEN_HOME_ITEMS ||
                                    page.id == SettingsPageId.HIDDEN_LIBRARY_ITEMS ||
                                    page.id == SettingsPageId.BLOCKED_ARTISTS
                                ) {
                                    return@forEach
                                }
                                val titleMatches = item.title.lowercase().contains(normalized)
                                val descriptionMatches =
                                    item.description
                                        .orEmpty()
                                        .lowercase()
                                        .contains(normalized)
                                val groupMatches = group.title.lowercase().contains(normalized)
                                val matchingValue =
                                    (item.choices.map { it.label } + listOfNotNull(item.valueLabel))
                                        .firstOrNull { it.contains(normalized, ignoreCase = true) }
                                if (titleMatches || descriptionMatches || groupMatches || matchingValue != null) {
                                    val context =
                                        when {
                                            matchingValue != null -> matchingValue
                                            descriptionMatches -> item.description
                                            groupMatches -> group.title
                                            else -> item.description
                                        }
                                    add(SettingsSearchResult(page, item, context))
                                }
                            }
                        }
                    }
                }
            }.distinctBy { it.page.id to it.item?.id }
    }
