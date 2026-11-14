/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.desktop.ui.components

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.metrolist.desktop.state.app.AppState
import com.metrolist.desktop.state.content.ArtistState
import com.metrolist.desktop.state.content.PlaylistAlbumState
import com.metrolist.desktop.state.playback.ListenTogetherState
import com.metrolist.desktop.state.playback.LyricsState
import com.metrolist.desktop.state.playback.PlayerState
import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.add_romanization_below_lyrics
import com.metrolist.shared.generated.resources.ai_translation
import com.metrolist.shared.generated.resources.automatic_mode
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.copy
import com.metrolist.shared.generated.resources.details
import com.metrolist.shared.generated.resources.display_gaps_between_sections
import com.metrolist.shared.generated.resources.edit
import com.metrolist.shared.generated.resources.edit_lyrics
import com.metrolist.shared.generated.resources.edit_song_description
import com.metrolist.shared.generated.resources.equalizer
import com.metrolist.shared.generated.resources.exit_fullscreen
import com.metrolist.shared.generated.resources.follow_agent_positioning_hints
import com.metrolist.shared.generated.resources.fullscreen
import com.metrolist.shared.generated.resources.hide_lyrics
import com.metrolist.shared.generated.resources.hide_queue
import com.metrolist.shared.generated.resources.lyrics_offset
import com.metrolist.shared.generated.resources.lyrics_respect_agent_positioning
import com.metrolist.shared.generated.resources.lyrics_show_translations
import com.metrolist.shared.generated.resources.manual_translate_this_track
import com.metrolist.shared.generated.resources.no_provider_results_found
import com.metrolist.shared.generated.resources.no_track_playing
import com.metrolist.shared.generated.resources.playback_speed
import com.metrolist.shared.generated.resources.refetch
import com.metrolist.shared.generated.resources.romanize_track
import com.metrolist.shared.generated.resources.save
import com.metrolist.shared.generated.resources.search
import com.metrolist.shared.generated.resources.select_to_cancel
import com.metrolist.shared.generated.resources.show_interval_indicator
import com.metrolist.shared.generated.resources.show_lyrics
import com.metrolist.shared.generated.resources.show_queue
import com.metrolist.shared.generated.resources.sleep_label
import com.metrolist.shared.generated.resources.sleep_timer
import com.metrolist.shared.generated.resources.stop_music_after_set_time
import com.metrolist.shared.generated.resources.stopping_after_current_song
import com.metrolist.shared.generated.resources.tempo_and_pitch
import com.metrolist.shared.generated.resources.view_song_info
import com.metrolist.shared.listentogether.ListenTogetherRoomRole
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ExpressiveSwitch
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.TempoPitchControls
import com.metrolist.shared.ui.components.lyrics.LyricsEditorScreen
import com.metrolist.shared.ui.equalizer.EqualizerDialog
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.menu.CursorDropdownMenu
import com.metrolist.shared.ui.menu.LyricsProviderDialog
import com.metrolist.shared.ui.menu.LyricsSearchDialog
import com.metrolist.shared.ui.menu.MediaContextMenu
import com.metrolist.shared.ui.menu.albumNavigationMenuAction
import com.metrolist.shared.ui.menu.artistLinksText
import com.metrolist.shared.ui.menu.artistNavigationMenuAction
import com.metrolist.shared.ui.menu.blockArtistMenuAction
import com.metrolist.shared.ui.menu.dislikeMenuAction
import com.metrolist.shared.ui.menu.equalizerMenuAction
import com.metrolist.shared.ui.menu.rememberDeleteUploadAction
import com.metrolist.shared.ui.menu.songPrimaryActions
import com.metrolist.shared.ui.menu.suggestToHostMenuAction
import com.metrolist.shared.ui.menu.tempoPitchMenuAction
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.utils.ShareUrls
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.GlobalContext

@Composable
fun LyricsActionsMenuCard(
    expanded: Boolean,
    colorScheme: ColorScheme,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onShowLyricsOffset: () -> Unit = { LyricsState.showLyricsOffsetEditor = true },
) {
    var respectAgentPositioning by remember { mutableStateOf(Settings.shared.respectAgentPositioning) }
    var showIntervalIndicator by remember { mutableStateOf(true) }
    var romanizeChecked by remember { mutableStateOf(Settings.shared.lyricsRomanizationEnabled) }
    var aiTranslationChecked by remember(LyricsState.manualAiTranslationEnabled) { mutableStateOf(LyricsState.manualAiTranslationEnabled) }
    var showTranslationsChecked by remember { mutableStateOf(Settings.shared.lyricsShowTranslations) }

    var showEditDialog by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf("") }
    var editingTrack by remember { mutableStateOf<SongItem?>(null) }
    PopupVisibility(visible = showEditDialog && editingTrack != null) {
        editingTrack?.let { track ->
            LyricsEditorScreen(
                songId = track.id,
                songTitle = track.title,
                rawLyrics = editText,
                onChanged = { LyricsState.refreshEditedLyrics(track) },
                onDismiss = {
                    showEditDialog = false
                    onDismiss()
                },
            )
        }
    }

    var showSearchDialog by remember { mutableStateOf(false) }
    var showProviderDialog by remember { mutableStateOf(false) }
    PopupVisibility(visible = showSearchDialog) {
        val currentTrack = PlayerState.currentTrack
        LyricsSearchDialog(
            initialTitle = LyricsState.queryTitle ?: currentTrack?.title.orEmpty(),
            initialArtist =
                LyricsState.queryArtist
                    ?: currentTrack
                        ?.artists
                        ?.joinToString { it.name }
                        .orEmpty(),
            onDismiss = { showSearchDialog = false },
            onSearch = { title, artist ->
                currentTrack?.let { LyricsState.fetchProviderResults(it, title, artist) }
                showSearchDialog = false
                showProviderDialog = true
            },
        )
    }

    PopupVisibility(visible = showProviderDialog) {
        LyricsProviderDialog(
            search = LyricsState.providerSearch,
            noResultsText = stringResource(Res.string.no_provider_results_found),
            onDismiss = { showProviderDialog = false },
            onSelect = { result ->
                PlayerState.currentTrack?.let { LyricsState.selectProviderResult(it, result) }
                showProviderDialog = false
                onDismiss()
            },
        )
    }

    CursorDropdownMenu(
        expanded = expanded,
        offset = DpOffset.Zero,
        onDismissRequest = onDismiss,
        modifier = modifier.width(320.dp),
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LyricsActionChip(
                    icon = MetrolistIcons.Outlined.Edit,
                    label = stringResource(Res.string.edit),
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f),
                ) {
                    editText = LyricsUtils.lyricsForEditing(LyricsState.currentLyrics, LyricsState.currentLyricsEntries)
                    editingTrack = PlayerState.currentTrack
                    showEditDialog = true
                }
                LyricsActionChip(
                    icon = MetrolistIcons.Outlined.Cached,
                    label = stringResource(Res.string.refetch),
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f),
                ) {
                    PlayerState.currentTrack?.let {
                        LyricsState.fetchLyrics(
                            it,
                            skipCache = true,
                            customTitle = LyricsState.queryTitle,
                            customArtist = LyricsState.queryArtist,
                        )
                    }
                    onDismiss()
                }
                LyricsActionChip(
                    icon = MetrolistIcons.Outlined.Search,
                    label = stringResource(Res.string.search),
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f),
                ) {
                    showSearchDialog = true
                }
                LyricsActionChip(
                    icon = MetrolistIcons.Outlined.ContentCopy,
                    label = stringResource(Res.string.copy),
                    colorScheme = colorScheme,
                    modifier = Modifier.weight(1f),
                ) {
                    val text = LyricsUtils.toPlainText(LyricsState.currentLyricsEntries)
                    if (text.isNotBlank()) {
                        AppState.copyToClipboard(text)
                    }
                    onDismiss()
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                color = colorScheme.onSurface.copy(alpha = 0.12f),
            )

            if (LyricsState.currentLyricsEntries.any { it.time > 0L }) {
                LyricsActionRow(
                    icon = MetrolistIcons.Outlined.FastForward,
                    title = stringResource(Res.string.lyrics_offset),
                    subtitle = "${LyricsState.currentLyricsOffsetMs}ms",
                    colorScheme = colorScheme,
                ) {
                    onDismiss()
                    onShowLyricsOffset()
                }
            }

            LyricsToggleRow(
                icon = MetrolistIcons.Outlined.Edit,
                title = stringResource(Res.string.lyrics_respect_agent_positioning),
                subtitle = stringResource(Res.string.follow_agent_positioning_hints),
                checked = respectAgentPositioning,
                colorScheme = colorScheme,
                onCheckedChange = {
                    respectAgentPositioning = it
                    Settings.shared.respectAgentPositioning = it
                },
            )

            LyricsToggleRow(
                icon = MetrolistIcons.Outlined.Edit,
                title = stringResource(Res.string.show_interval_indicator),
                subtitle = stringResource(Res.string.display_gaps_between_sections),
                checked = showIntervalIndicator,
                colorScheme = colorScheme,
                onCheckedChange = { showIntervalIndicator = it },
            )

            LyricsToggleRow(
                icon = MetrolistIcons.Outlined.Translate,
                title = stringResource(Res.string.ai_translation),
                subtitle =
                    if (Settings.shared.lyricsAiActivationMode ==
                        "Manual"
                    ) {
                        stringResource(Res.string.manual_translate_this_track)
                    } else {
                        stringResource(Res.string.automatic_mode)
                    },
                checked = aiTranslationChecked,
                enabled = Settings.shared.lyricsAiTranslationEnabled,
                colorScheme = colorScheme,
                onCheckedChange = {
                    aiTranslationChecked = it
                    LyricsState.setManualLyricsTranslation(it)
                },
            )

            if (LyricsState.currentLyricsEntries.any {
                    !it.translatedText.isNullOrBlank() ||
                        !it.backgroundTranslatedText.isNullOrBlank()
                }
            ) {
                LyricsToggleRow(
                    icon = MetrolistIcons.Outlined.Translate,
                    title = stringResource(Res.string.lyrics_show_translations),
                    checked = showTranslationsChecked,
                    colorScheme = colorScheme,
                    onCheckedChange = {
                        showTranslationsChecked = it
                        Settings.shared.lyricsShowTranslations = it
                    },
                )
            }

            LyricsToggleRow(
                icon = MetrolistIcons.Outlined.Translate,
                title = stringResource(Res.string.romanize_track),
                subtitle = stringResource(Res.string.add_romanization_below_lyrics),
                checked = romanizeChecked,
                colorScheme = colorScheme,
                onCheckedChange = {
                    romanizeChecked = it
                    Settings.shared.lyricsRomanizationEnabled = it
                },
            )
        }
    }
}

@Composable
private fun LyricsActionChip(
    icon: ImageVector,
    label: String,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurface, maxLines = 1)
        }
    }
}

@Composable
private fun LyricsActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
            }
        }
    }
}

@Composable
private fun LyricsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.5f
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = enabled) { onCheckedChange(!checked) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary.copy(alpha = contentAlpha),
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface.copy(alpha = contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.basicMarquee(),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(),
                )
            }
        }
        ExpressiveSwitch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
fun PlayerMenuPanel(
    expanded: Boolean = true,
    offset: DpOffset = DpOffset.Zero,
    onDismiss: () -> Unit,
    onShowAddToPlaylistDialog: () -> Unit,
    onShowDetailsDialog: (() -> Unit)?,
    onShowSleepTimerDialog: (() -> Unit)?,
    onToggleQueue: (() -> Unit)? = null,
    isQueueActive: Boolean = false,
    onToggleLyrics: (() -> Unit)? = null,
    isLyricsActive: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
    isFullscreen: Boolean = false,
) {
    val currentTrack = PlayerState.currentTrack
    val playback = LocalPlaybackController.current
    var editingSong by remember { mutableStateOf<SongItem?>(null) }
    editingSong?.let { song ->
        SongMetadataEditorDialog(
            song = song,
            onSaved = {
                editingSong = null
                onDismiss()
            },
            onDismiss = { editingSong = null },
        )
    }
    val deleteUploadAction =
        currentTrack?.takeIf { expanded }?.let {
            rememberDeleteUploadAction(it, remember { GlobalContext.get().get<MusicDatabase>() }, onDismiss)
        }
    val artistAction = artistNavigationMenuAction(currentTrack?.artists.orEmpty(), ArtistState::fetchArtistData, onDismiss)
    val listenTogetherManager = ListenTogetherState.listenTogetherManager
    val isInListenTogetherRoom by listenTogetherManager.isInRoom.collectAsState()
    val listenTogetherRole by listenTogetherManager.role.collectAsState()
    val isListenTogetherGuest =
        PlayerState.isListenTogetherGuest() ||
            (isInListenTogetherRoom && listenTogetherRole == ListenTogetherRoomRole.GUEST)
    var showTempoPitch by remember { mutableStateOf(false) }
    var showEqualizer by remember { mutableStateOf(false) }
    if (showEqualizer) {
        EqualizerDialog(
            onDismiss = { showEqualizer = false },
            rememberProfilePicker = { onPicked -> rememberMetroEqProfilePicker(onPicked) },
        )
    }
    val speed by PlayerState.player.playbackSpeed.collectAsState()
    val pitch by PlayerState.player.playbackPitch.collectAsState()
    val varispeed = Settings.shared.varispeed
    PopupVisibility(visible = showTempoPitch) {
        AlertDialog(
            onDismissRequest = { showTempoPitch = false },
            title = { Text(stringResource(if (varispeed) Res.string.playback_speed else Res.string.tempo_and_pitch)) },
            text = {
                TempoPitchControls(
                    speed,
                    pitch,
                    PlayerState::setPlaybackSpeed,
                    PlayerState::setPlaybackPitch,
                    speedEnabled = !isInListenTogetherRoom,
                    varispeed = varispeed,
                    onDone = { showTempoPitch = false },
                )
            },
            confirmButton = {},
        )
    }
    val sleepActive = PlayerState.sleepTimerEnabled
    val sleepTimeLeft = if (expanded && sleepActive) PlayerState.sleepTimerTimeLeftMs.collectAsState().value else 0L
    MediaContextMenu(
        expanded = expanded && editingSong == null,
        offset = offset,
        onDismiss = onDismiss,
        preferSidePlacement = true,
        tonalElevation = 6.dp,
        title = currentTrack?.title ?: stringResource(Res.string.no_track_playing),
        subtitle = currentTrack?.artists?.joinToString(", ") { it.displayName },
        subtitleLinks =
            artistLinksText(currentTrack?.artists.orEmpty()) {
                ArtistState.fetchArtistData(it)
                onDismiss()
            },
        thumbnail = PlayerState.currentArtworkUrl,
        isExplicit = currentTrack?.isExplicit == true,
        primaryActions =
            songPrimaryActions(
                onDismiss = onDismiss,
                isGuest = isListenTogetherGuest,
                playbackEnabled = currentTrack?.isUnavailable == false,
                onStartRadio = currentTrack?.let { track -> { playback.startRadio(track) } },
                onAddToPlaylist =
                    currentTrack?.let {
                        {
                            onShowAddToPlaylistDialog()
                            onDismiss()
                        }
                    },
                onShare =
                    currentTrack?.let { track ->
                        {
                            val url = ShareUrls.song(track.id)
                            AppState.shareLink(url, track.title)
                        }
                    },
            ),
        actionGroups =
            listOf(
                listOfNotNull(
                    artistAction,
                    albumNavigationMenuAction(
                        onDismiss,
                        currentTrack?.album?.id?.let { albumId -> { PlaylistAlbumState.fetchAlbumData(albumId) } },
                        currentTrack?.album?.name,
                    ),
                ),
                listOfNotNull(
                    deleteUploadAction,
                    currentTrack?.let { track ->
                        PlayerMenuAction(
                            MetrolistIcons.Outlined.Edit,
                            stringResource(Res.string.edit),
                            stringResource(Res.string.edit_song_description),
                        ) { editingSong = track }
                    },
                    dislikeMenuAction(
                        currentTrack?.let { PlayerState.isDisliked(it.id) } == true,
                        onDismiss,
                        currentTrack?.let { track -> { PlayerState.toggleDislike(track) } },
                        showDescription = true,
                    ),
                    currentTrack?.artists?.firstOrNull { it.id != null }?.let { artist ->
                        blockArtistMenuAction(onDismiss, { PlayerState.blockArtist(artist) }, artist.displayName)
                    },
                ),
                listOfNotNull(
                    equalizerMenuAction(onOpen = { showEqualizer = true }),
                    tempoPitchMenuAction(varispeed) { showTempoPitch = true },
                    onShowDetailsDialog?.let { action ->
                        PlayerMenuAction(
                            MetrolistIcons.Outlined.Info,
                            stringResource(Res.string.details),
                            stringResource(Res.string.view_song_info),
                        ) {
                            action()
                            onDismiss()
                        }
                    },
                    (onShowSleepTimerDialog != null || sleepActive).takeIf { it && !isListenTogetherGuest }?.let {
                        PlayerMenuAction(
                            MetrolistIcons.Outlined.Bedtime,
                            when {
                                PlayerState.sleepTimerStopAfterCurrentSong -> {
                                    stringResource(Res.string.stopping_after_current_song)
                                }

                                sleepActive -> {
                                    stringResource(Res.string.sleep_label) +
                                        ": ${formatSleepTime(sleepTimeLeft)}"
                                }

                                else -> {
                                    stringResource(Res.string.sleep_timer)
                                }
                            },
                            if (sleepActive) {
                                stringResource(
                                    Res.string.select_to_cancel,
                                )
                            } else {
                                stringResource(Res.string.stop_music_after_set_time)
                            },
                        ) {
                            if (sleepActive) PlayerState.clearSleepTimer() else onShowSleepTimerDialog?.invoke()
                            onDismiss()
                        }
                    },
                    currentTrack?.let { track -> songDownloadAction(track, onDismiss) },
                ),
                listOfNotNull(
                    currentTrack?.takeIf { isListenTogetherGuest }?.let { track ->
                        suggestToHostMenuAction(onDismiss) { ListenTogetherState.suggestTrack(track) }
                    },
                ),
                listOfNotNull(
                    onToggleQueue?.let { toggle ->
                        val queueIcon =
                            if (isQueueActive) {
                                MetrolistIcons.AutoMirrored.Filled.QueueMusic
                            } else {
                                MetrolistIcons.AutoMirrored.Outlined.QueueMusic
                            }
                        val queueLabel =
                            if (isQueueActive) {
                                stringResource(Res.string.hide_queue)
                            } else {
                                stringResource(Res.string.show_queue)
                            }
                        PlayerMenuAction(queueIcon, queueLabel) {
                            toggle()
                            onDismiss()
                        }
                    },
                    onToggleLyrics?.let { toggle ->
                        val lyricsLabel =
                            if (isLyricsActive) {
                                stringResource(Res.string.hide_lyrics)
                            } else {
                                stringResource(Res.string.show_lyrics)
                            }
                        PlayerMenuAction(MetrolistIcons.Filled.Lyrics, lyricsLabel) {
                            toggle()
                            onDismiss()
                        }
                    },
                    onToggleFullscreen?.let { toggle ->
                        val fullscreenIcon =
                            if (isFullscreen) {
                                MetrolistIcons.Outlined.FullscreenExit
                            } else {
                                MetrolistIcons.Outlined.Fullscreen
                            }
                        val fullscreenLabel =
                            if (isFullscreen) {
                                stringResource(Res.string.exit_fullscreen)
                            } else {
                                stringResource(Res.string.fullscreen)
                            }
                        PlayerMenuAction(fullscreenIcon, fullscreenLabel) {
                            toggle()
                            onDismiss()
                        }
                    },
                ),
            ),
    )
}

private fun formatSleepTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}
