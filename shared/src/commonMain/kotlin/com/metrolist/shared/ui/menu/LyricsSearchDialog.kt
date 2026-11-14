@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.menu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.lyrics_song_artist
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.search_lyrics
import com.metrolist.shared.generated.resources.song_title
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.AlertDialog
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
    AlertDialog(
        onDismissRequest = onDismiss,
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
            Button(
                onClick = submitSearch,
                enabled = title.text.isNotBlank(),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.tvFocusHighlight(),
            ) {
                Text(stringResource(Res.string.search))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.cancel)) }
        },
    )
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
