@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.lyrics_search_online_query
import com.metrolist.shared.generated.resources.lyrics_song_artist
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_lyrics
import com.metrolist.shared.generated.resources.search_online
import com.metrolist.shared.generated.resources.search_online_short
import com.metrolist.shared.generated.resources.song_title
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.withStaticCorners
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

@Composable
fun LyricsSearchDialog(
    initialTitle: String,
    initialArtist: String,
    onDismiss: () -> Unit,
    onSearch: (title: String, artist: String) -> Unit,
    onSearchOnline: ((query: String) -> Unit)? = null,
) {
    val title = remember(initialTitle) { TextFieldState(initialTitle) }
    val artist = remember(initialArtist) { TextFieldState(initialArtist) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val submitSearch = {
        if (title.text.isNotBlank()) {
            keyboardController?.hide()
            onSearch(
                title.text.toString().trim(),
                artist.text.toString().trim(),
            )
        }
    }
    val onlineQuery =
        onSearchOnline?.let {
            stringResource(
                Res.string.lyrics_search_online_query,
                artist.text.toString().trim(),
                title.text.toString().trim(),
            ).trim()
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        // The online actions share one row, so each button draws its own focus outline.
        outlineActions = onSearchOnline == null,
        icon = { Icon(MetrolistIcons.Default.Search, contentDescription = null) },
        title = { Text(stringResource(Res.string.search_lyrics)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScrollWithDrag(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LyricsSearchTextField(
                    state = title,
                    label = stringResource(Res.string.song_title),
                    onSearch = submitSearch,
                )
                LyricsSearchTextField(
                    state = artist,
                    label = stringResource(Res.string.lyrics_song_artist),
                    onSearch = submitSearch,
                )
            }
        },
        confirmButton = {
            if (onSearchOnline != null && onlineQuery != null) {
                LyricsSearchOnlineActions(
                    enabled = title.text.isNotBlank(),
                    onCancel = onDismiss,
                    onSearchOnline = {
                        keyboardController?.hide()
                        onSearchOnline(onlineQuery)
                    },
                    onSearch = submitSearch,
                )
            } else {
                Button(
                    onClick = submitSearch,
                    enabled = title.text.isNotBlank(),
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.tvFocusHighlight(),
                ) {
                    Text(stringResource(Res.string.search))
                }
            }
        },
        dismissButton =
            if (onSearchOnline == null) {
                { OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) } }
            } else {
                null
            },
    )
}

internal enum class LyricsSearchActionLayout {
    FULL,
    SHORT_ONLINE,
    ICON_CANCEL,
}

// Prefer a full Cancel label over a full Search online label; collapse Cancel to an icon last.
internal fun Density.lyricsSearchActionLayout(
    availableWidth: Int,
    cancelLabelWidth: Int,
    onlineLabelWidth: Int,
    shortOnlineLabelWidth: Int,
    searchLabelWidth: Int,
): LyricsSearchActionLayout {
    val padding =
        LyricsSearchActionPadding.calculateLeftPadding(LayoutDirection.Ltr).roundToPx() +
            LyricsSearchActionPadding.calculateRightPadding(LayoutDirection.Ltr).roundToPx()
    val minWidth = ButtonDefaults.MinWidth.roundToPx()

    fun button(labelWidth: Int) = maxOf(labelWidth + padding, minWidth)

    val group = { onlineWidth: Int ->
        button(onlineWidth) + ButtonGroupDefaults.ConnectedSpaceBetween.roundToPx() + button(searchLabelWidth)
    }
    val gap = LyricsSearchActionSpacing.roundToPx()
    return when {
        button(cancelLabelWidth) + gap + group(onlineLabelWidth) <= availableWidth -> LyricsSearchActionLayout.FULL
        button(cancelLabelWidth) + gap + group(shortOnlineLabelWidth) <= availableWidth -> LyricsSearchActionLayout.SHORT_ONLINE
        else -> LyricsSearchActionLayout.ICON_CANCEL
    }
}

private val LyricsSearchActionPadding = ButtonDefaults.SmallContentPadding
private val LyricsSearchActionSpacing = 8.dp

@Composable
private fun LyricsSearchOnlineActions(
    enabled: Boolean,
    onCancel: () -> Unit,
    onSearchOnline: () -> Unit,
    onSearch: () -> Unit,
) {
    val cancelLabel = stringResource(Res.string.cancel)
    val onlineLabel = stringResource(Res.string.search_online)
    val shortOnlineLabel = stringResource(Res.string.search_online_short)
    val searchLabel = stringResource(Res.string.search)
    val textStyle = MaterialTheme.typography.labelLarge
    val textMeasurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val layout =
            with(LocalDensity.current) {
                lyricsSearchActionLayout(
                    availableWidth = constraints.maxWidth,
                    cancelLabelWidth = textMeasurer.measure(cancelLabel, textStyle).size.width,
                    onlineLabelWidth = textMeasurer.measure(onlineLabel, textStyle).size.width,
                    shortOnlineLabelWidth = textMeasurer.measure(shortOnlineLabel, textStyle).size.width,
                    searchLabelWidth = textMeasurer.measure(searchLabel, textStyle).size.width,
                )
            }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(LyricsSearchActionSpacing, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (layout == LyricsSearchActionLayout.ICON_CANCEL) {
                OutlinedIconButton(
                    onClick = onCancel,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(ButtonDefaults.MinHeight).tvFocusHighlight(CircleShape),
                ) {
                    Icon(MetrolistIcons.Outlined.Close, contentDescription = cancelLabel)
                }
            } else {
                OutlinedButton(
                    onClick = onCancel,
                    shapes = ButtonDefaults.shapes(),
                    contentPadding = LyricsSearchActionPadding,
                    modifier = Modifier.tvFocusHighlight(CircleShape),
                ) {
                    Text(cancelLabel, maxLines = 1)
                }
            }
            val labels = listOf(if (layout == LyricsSearchActionLayout.FULL) onlineLabel else shortOnlineLabel, searchLabel)
            val callbacks = listOf(onSearchOnline, onSearch)
            ButtonGroup(
                overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) },
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { index, label ->
                    customItem(
                        buttonGroupContent = {
                            val interactionSource = remember { MutableInteractionSource() }
                            val shapes =
                                if (index == 0) {
                                    ButtonGroupDefaults.connectedLeadingButtonShapes()
                                } else {
                                    ButtonGroupDefaults.connectedTrailingButtonShapes()
                                }.withStaticCorners()
                            Button(
                                onClick = callbacks[index],
                                enabled = enabled,
                                interactionSource = interactionSource,
                                shapes = ButtonDefaults.shapes(shape = shapes.shape, pressedShape = shapes.pressedShape),
                                contentPadding = LyricsSearchActionPadding,
                                modifier = Modifier.animateWidth(interactionSource, 12.dp).tvFocusHighlight(shapes.shape),
                            ) {
                                Text(label, maxLines = 1)
                            }
                        },
                        menuContent = {
                            DropdownMenuItem(text = { Text(label) }, onClick = callbacks[index], enabled = enabled)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun LyricsSearchTextField(
    state: TextFieldState,
    label: String,
    onSearch: () -> Unit,
) {
    val isTv = LocalIsTv.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var tvKeyboardRequested by remember { mutableStateOf(false) }
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        trailingIcon = {
            IconButton(onClick = state::clearText, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.Default.Clear, stringResource(Res.string.clear))
            }
        },
        lineLimits = TextFieldLineLimits.SingleLine,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search, showKeyboardOnFocus = !isTv || tvKeyboardRequested),
        onKeyboardAction = { onSearch() },
        modifier =
            Modifier
                .fillMaxWidth()
                .tvFocusHighlight()
                .onFocusChanged { if (!it.isFocused) tvKeyboardRequested = false }
                .onInterceptKeyBeforeSoftKeyboard { event ->
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
                    } else if (isTv && (event.key == Key.DirectionUp || event.key == Key.DirectionDown)) {
                        if (event.type == KeyEventType.KeyDown) {
                            focusManager.moveFocus(if (event.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        }
                        true
                    } else {
                        false
                    }
                },
    )
}
