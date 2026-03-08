/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.desktop.state.app.UiState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.clear
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.keyboard_shortcuts
import com.metrolist.shared.generated.resources.keyboard_shortcuts_sheet
import com.metrolist.shared.generated.resources.no_shortcuts_matching
import com.metrolist.shared.generated.resources.or
import com.metrolist.shared.generated.resources.press_x_or_esc_to_dismiss
import com.metrolist.shared.generated.resources.search_shortcuts
import com.metrolist.shared.generated.resources.shortcut_cycle_repeat
import com.metrolist.shared.generated.resources.shortcut_developer_log
import com.metrolist.shared.generated.resources.shortcut_dismiss_dialogs
import com.metrolist.shared.generated.resources.shortcut_jump_percent
import com.metrolist.shared.generated.resources.shortcut_mute_unmute
import com.metrolist.shared.generated.resources.shortcut_next_track
import com.metrolist.shared.generated.resources.shortcut_open_search
import com.metrolist.shared.generated.resources.shortcut_open_settings
import com.metrolist.shared.generated.resources.shortcut_play_pause
import com.metrolist.shared.generated.resources.shortcut_previous_track
import com.metrolist.shared.generated.resources.shortcut_reload_app
import com.metrolist.shared.generated.resources.shortcut_seek_backward_forward
import com.metrolist.shared.generated.resources.shortcut_toggle_captions
import com.metrolist.shared.generated.resources.shortcut_toggle_device_connect
import com.metrolist.shared.generated.resources.shortcut_toggle_expanded
import com.metrolist.shared.generated.resources.shortcut_toggle_fullscreen_video
import com.metrolist.shared.generated.resources.shortcut_toggle_lyrics
import com.metrolist.shared.generated.resources.shortcut_toggle_queue
import com.metrolist.shared.generated.resources.shortcut_toggle_shortcuts
import com.metrolist.shared.generated.resources.shortcut_toggle_shuffle
import com.metrolist.shared.generated.resources.shortcut_toggle_video
import com.metrolist.shared.generated.resources.shortcut_volume_step
import com.metrolist.shared.generated.resources.shortcut_volume_up_down
import com.metrolist.shared.generated.resources.shortcuts_music_video
import com.metrolist.shared.generated.resources.shortcuts_navigation_interface
import com.metrolist.shared.generated.resources.shortcuts_playback
import com.metrolist.shared.generated.resources.shortcuts_volume
import com.metrolist.shared.ui.components.Dialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import com.metrolist.shared.utils.OsUtil
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds

private data class ShortcutItem(
    val description: String,
    val keyCombos: List<List<String>>,
)

private data class ShortcutCategory(
    val title: String,
    val shortcuts: List<ShortcutItem>,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShortcutsOverlay(
    colorScheme: ColorScheme,
    onDismiss: () -> Unit,
) {
    val modKey = if (OsUtil.isMac) "⌘" else "Ctrl"
    val altKey = if (OsUtil.isMac) "⌥" else "Alt"
    val shiftKey = if (OsUtil.isMac) "⇧" else "Shift"

    val categories = shortcutCategories(modKey, altKey, shiftKey)

    val searchState = rememberTextFieldState()
    val searchQuery = searchState.text.toString()
    val focusRequester = remember { FocusRequester() }

    val filteredCategories =
        remember(searchQuery, categories) {
            if (searchQuery.isBlank()) {
                categories
            } else {
                val q = searchQuery.trim().lowercase()
                categories.mapNotNull { cat ->
                    val matchingShortcuts =
                        cat.shortcuts.filter { item ->
                            item.description.lowercase().contains(q) ||
                                item.keyCombos.any { combo ->
                                    combo.any { key -> key.lowercase().contains(q) }
                                }
                        }
                    if (matchingShortcuts.isNotEmpty()) {
                        cat.copy(shortcuts = matchingShortcuts)
                    } else {
                        null
                    }
                }
            }
        }

    val paneTitleText = stringResource(Res.string.keyboard_shortcuts_sheet)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        LaunchedEffect(Unit) {
            delay(60.milliseconds)
            try {
                focusRequester.requestFocus()
            } catch (_: Exception) {
            }
        }
        Surface(
            modifier =
                Modifier
                    .widthIn(max = 680.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 700.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .semantics { paneTitle = paneTitleText },
            shape = RoundedCornerShape(24.dp),
            color = colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = stringResource(Res.string.keyboard_shortcuts),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(Res.string.press_x_or_esc_to_dismiss, modKey),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, shapes = IconButtonDefaults.shapes()) {
                        Icon(
                            imageVector = MetrolistIcons.Default.Close,
                            contentDescription = stringResource(Res.string.close),
                            tint = colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Default.Search,
                            contentDescription = null,
                            tint = colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        BasicTextField(
                            state = searchState,
                            modifier = Modifier.weight(1f).focusRequester(focusRequester),
                            textStyle =
                                MaterialTheme.typography.bodyMedium.copy(
                                    color = colorScheme.onSurface,
                                ),
                            cursorBrush = SolidColor(colorScheme.primary),
                            lineLimits = TextFieldLineLimits.SingleLine,
                            decorator = { innerTextField ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(Res.string.search_shortcuts),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                innerTextField()
                            },
                        )
                        if (searchQuery.isNotEmpty()) {
                            Icon(
                                imageVector = MetrolistIcons.Default.Close,
                                contentDescription = stringResource(Res.string.clear),
                                tint = colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).clickable { searchState.setTextAndPlaceCursorAtEnd("") },
                            )
                        }
                    }
                }

                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.35f))

                val scrollState = rememberScrollState()
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScrollWithDrag(scrollState)
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (filteredCategories.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(Res.string.no_shortcuts_matching, searchQuery),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        filteredCategories.forEach { category ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = category.title,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = colorScheme.primary,
                                )

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                                    border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                        category.shortcuts.forEachIndexed { index, shortcut ->
                                            Row(
                                                modifier =
                                                    Modifier
                                                        .fillMaxWidth()
                                                        .padding(vertical = 8.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    text = shortcut.description,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = colorScheme.onSurface,
                                                    modifier = Modifier.weight(1f).padding(end = 16.dp),
                                                )

                                                FlowRow(
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                                ) {
                                                    shortcut.keyCombos.forEachIndexed { cIdx, combo ->
                                                        if (cIdx > 0) {
                                                            Text(
                                                                text = stringResource(Res.string.or),
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = colorScheme.onSurfaceVariant,
                                                                modifier =
                                                                    Modifier
                                                                        .align(
                                                                            Alignment.CenterVertically,
                                                                        ).padding(horizontal = 2.dp),
                                                            )
                                                        }
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                            verticalAlignment = Alignment.CenterVertically,
                                                        ) {
                                                            combo.forEach { keyLabel ->
                                                                KeyBadge(
                                                                    label = keyLabel,
                                                                    colorScheme = colorScheme,
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }

                                            if (index < category.shortcuts.lastIndex) {
                                                HorizontalDivider(
                                                    color = colorScheme.outlineVariant.copy(alpha = 0.15f),
                                                    modifier = Modifier.padding(vertical = 2.dp),
                                                )
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
}

@Composable
private fun KeyBadge(
    label: String,
    colorScheme: ColorScheme,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colorScheme.surfaceContainerHighest,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, colorScheme.outline.copy(alpha = 0.25f)),
    ) {
        Text(
            text = label,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun shortcutCategories(
    modKey: String,
    altKey: String,
    shiftKey: String,
): List<ShortcutCategory> =
    listOf(
        ShortcutCategory(
            title = stringResource(Res.string.shortcuts_playback),
            shortcuts =
                listOf(
                    ShortcutItem(stringResource(Res.string.shortcut_play_pause), listOf(listOf("Space"))),
                    ShortcutItem(stringResource(Res.string.shortcut_seek_backward_forward), listOf(listOf("←"), listOf("→"))),
                    ShortcutItem(stringResource(Res.string.shortcut_jump_percent), listOf(listOf("0 – 9"))),
                    ShortcutItem(stringResource(Res.string.shortcut_next_track), listOf(listOf(modKey, "→"), listOf("N"))),
                    ShortcutItem(stringResource(Res.string.shortcut_previous_track), listOf(listOf(modKey, "←"), listOf("P"))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_expanded), listOf(listOf("T"))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_shuffle), listOf(listOf("S"))),
                    ShortcutItem(stringResource(Res.string.shortcut_cycle_repeat), listOf(listOf("R"))),
                ),
        ),
        ShortcutCategory(
            title = stringResource(Res.string.shortcuts_volume),
            shortcuts =
                listOf(
                    ShortcutItem(stringResource(Res.string.shortcut_volume_up_down), listOf(listOf("↑"), listOf("↓"))),
                    ShortcutItem(stringResource(Res.string.shortcut_volume_step), listOf(listOf("+"), listOf("-"))),
                    ShortcutItem(stringResource(Res.string.shortcut_mute_unmute), listOf(listOf("M"))),
                ),
        ),
        ShortcutCategory(
            title = stringResource(Res.string.shortcuts_music_video),
            shortcuts =
                listOf(
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_video), listOf(listOf("V"))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_fullscreen_video), listOf(listOf("F"))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_captions), listOf(listOf("C"))),
                ),
        ),
        ShortcutCategory(
            title = stringResource(Res.string.shortcuts_navigation_interface),
            shortcuts =
                listOf(
                    ShortcutItem(
                        stringResource(Res.string.shortcut_open_search),
                        listOf(listOf(modKey, "K"), listOf("/"), listOf(modKey, "F")),
                    ),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_lyrics), listOf(listOf("L"))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_queue), listOf(listOf("Q"))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_device_connect), listOf(listOf("D"))),
                    ShortcutItem(stringResource(Res.string.shortcut_open_settings), listOf(listOf(modKey, ","))),
                    ShortcutItem(stringResource(Res.string.shortcut_toggle_shortcuts), listOf(listOf(modKey, "`"))),
                    ShortcutItem(stringResource(Res.string.shortcut_developer_log), listOf(listOf(modKey, shiftKey, "L"))),
                    ShortcutItem(stringResource(Res.string.shortcut_dismiss_dialogs), listOf(listOf("Esc"))),
                    ShortcutItem(stringResource(Res.string.shortcut_reload_app), listOf(listOf("F5"))),
                ),
        ),
    )
