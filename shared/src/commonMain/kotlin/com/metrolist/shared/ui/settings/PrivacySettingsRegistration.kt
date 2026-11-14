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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenHomeItems
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import com.metrolist.shared.settings.HiddenLibraryItems
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

@Composable
internal fun SettingsUiRegistryBuilder.registerPrivacySettings(host: SharedSettingsHost) {
    val settings = Settings.shared
    var pendingRestore by remember { mutableStateOf<RestoreRequest?>(null) }
    page(
        id = SettingsPageId.PRIVACY,
        title = stringResource(Res.string.privacy_settings_title),
        subtitle = stringResource(Res.string.settings_privacy_description),
        icon = MetrolistIcons.Outlined.Security,
        tone = SettingsTone.CYAN,
    ) {
        group("history", stringResource(Res.string.history)) {
            switch("pauseListenHistory", stringResource(Res.string.pause_listen_history), checked = settings.pauseListenHistory) {
                settings.pauseListenHistory = it
            }
            switch(
                id = "historyPlaylists",
                title = stringResource(Res.string.settings_history_playlists),
                description = stringResource(Res.string.settings_history_playlists_desc),
                checked = settings.historyPlaylistsEnabled,
            ) { settings.historyPlaylistsEnabled = it }
            switch(
                id = "queueOfflineHistory",
                title = stringResource(Res.string.queue_youtube_history_when_offline),
                description =
                    stringResource(
                        if (settings.pauseListenHistory) {
                            Res.string.queue_youtube_history_paused_desc
                        } else {
                            Res.string.queue_youtube_history_when_offline_desc
                        },
                    ),
                enabled = !settings.pauseListenHistory,
                checked = settings.queueYouTubeHistoryWhenOffline,
            ) { settings.queueYouTubeHistoryWhenOffline = it }
            if (!settings.pauseListenHistory) {
                inlineSlider(
                    id = "historyDuration",
                    title = stringResource(Res.string.history_duration),
                    description = stringResource(Res.string.history_duration_desc),
                    valueLabel = stringResource(Res.string.settings_seconds_value, settings.historyDuration.roundToInt()),
                    value = settings.historyDuration,
                    defaultValue = settings.defaultValue(settings::historyDuration),
                    range = 5f..60f,
                    steps = 10,
                ) { settings.historyDuration = it.roundToInt().toFloat() }
            }
            switch("pauseSearchHistory", stringResource(Res.string.pause_search_history), checked = settings.pauseSearchHistory) {
                settings.pauseSearchHistory = it
            }
            switch(
                id = "hideRecentSearches",
                title = stringResource(Res.string.hide_recent_searches),
                checked = settings.hideRecentSearches,
            ) { settings.hideRecentSearches = it }
            host.onClearSearchHistory?.let { clearSearchHistory ->
                action(
                    id = "clearSearchHistory",
                    title = stringResource(Res.string.clear_search_history),
                    description = stringResource(Res.string.settings_clear_search_history_description),
                    confirmationMessage = stringResource(Res.string.settings_clear_search_history_confirm_message),
                    onClick = clearSearchHistory,
                )
            }
            if (host.platform != SettingsPlatform.DESKTOP) {
                switch(
                    id = "blockScreenshots",
                    title =
                        stringResource(
                            if (host.platform ==
                                SettingsPlatform.IOS
                            ) {
                                Res.string.ios_screen_privacy
                            } else {
                                Res.string.block_screenshots
                            },
                        ),
                    description =
                        stringResource(
                            if (host.platform ==
                                SettingsPlatform.IOS
                            ) {
                                Res.string.ios_screen_privacy_description
                            } else {
                                Res.string.block_screenshots_desc
                            },
                        ),
                    checked = settings.blockScreenshots,
                ) { settings.blockScreenshots = it }
            }
        }
        group("diagnostics", stringResource(Res.string.privacy_diagnostics)) {
            switch(
                id = "sentry",
                title = stringResource(Res.string.sentry_reporting),
                description = stringResource(Res.string.sentry_reporting_desc),
                checked = settings.sentryEnabled,
            ) {
                host.onSettingChanged(SettingsChangeEffect.SENTRY, it)
            }
            if (host.platform != SettingsPlatform.ANDROID) {
                switch(
                    id = "diagnosticLogging",
                    title = stringResource(Res.string.settings_diagnostic_logging),
                    description = stringResource(Res.string.settings_diagnostic_logging_description),
                    checked = settings.enableLogging,
                ) {
                    settings.enableLogging = it
                    host.onSettingChanged(SettingsChangeEffect.LOGGING, it)
                }
            }
            host.privacyDiagnosticActions.forEach { item ->
                action(item.id, item.title, item.description, enabled = item.enabled, onClick = item.onClick)
            }
        }
        group("blocklists", stringResource(Res.string.privacy_blocklists)) {
            if (SettingsPageId.BLOCKED_ARTISTS in host.availablePages) {
                navigation(
                    id = "blockedArtists",
                    title = stringResource(Res.string.blocked_artists),
                    destination = SettingsPageId.BLOCKED_ARTISTS,
                    description = stringResource(Res.string.blocked_artists_desc),
                )
            }
            if (SettingsPageId.HIDDEN_HOME_ITEMS in host.availablePages) {
                navigation(
                    id = "hiddenHomeItems",
                    title = stringResource(Res.string.hidden_home_items),
                    destination = SettingsPageId.HIDDEN_HOME_ITEMS,
                    description = stringResource(Res.string.hidden_home_items_desc),
                )
            }
            if (SettingsPageId.HIDDEN_LIBRARY_ITEMS in host.availablePages) {
                navigation(
                    id = "hiddenItems",
                    title = stringResource(Res.string.hidden_playlists),
                    destination = SettingsPageId.HIDDEN_LIBRARY_ITEMS,
                    description = stringResource(Res.string.hidden_playlists_desc),
                )
            }
        }
    }

    if (SettingsPageId.HIDDEN_HOME_ITEMS in host.availablePages) {
        val hiddenItems = remember(settings.hiddenHomeItemsJson) { HiddenHomeItems.all() }
        page(
            id = SettingsPageId.HIDDEN_HOME_ITEMS,
            title = stringResource(Res.string.hidden_home_items),
            subtitle = stringResource(Res.string.hidden_home_items_desc),
            icon = MetrolistIcons.Filled.VisibilityOff,
            tone = SettingsTone.CYAN,
        ) {
            group("hiddenHomeItems", stringResource(Res.string.hidden_home_items)) {
                if (hiddenItems.isEmpty()) {
                    action(
                        id = "empty",
                        title = stringResource(Res.string.hidden_home_items_empty),
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    hiddenItems.forEach { item ->
                        custom(
                            id = "hiddenHome:${item.type}:${item.id}",
                            title = item.title,
                            description = stringResource(Res.string.show_on_home),
                        ) { onSettingValueChanged ->
                            HiddenItemRow(
                                title = item.title,
                                description = stringResource(Res.string.show_on_home),
                                onRemove = {
                                    pendingRestore =
                                        RestoreRequest.HiddenHome(item.key, item.title, onSettingValueChanged)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (SettingsPageId.HIDDEN_LIBRARY_ITEMS in host.availablePages) {
        val hiddenItems = remember(settings.hiddenLibraryPlaylistsJson) { HiddenLibraryItems.all() }
        page(
            id = SettingsPageId.HIDDEN_LIBRARY_ITEMS,
            title = stringResource(Res.string.hidden_playlists),
            subtitle = stringResource(Res.string.hidden_playlists_desc),
            icon = MetrolistIcons.Filled.Visibility,
            tone = SettingsTone.CYAN,
        ) {
            group("hiddenItems", stringResource(Res.string.hidden_playlists)) {
                if (hiddenItems.isEmpty()) {
                    action(
                        id = "empty",
                        title = stringResource(Res.string.hidden_playlists_empty),
                        description = stringResource(Res.string.hidden_playlists_empty_desc),
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    hiddenItems.forEach { item ->
                        val typeLabel =
                            when (item.type) {
                                HiddenLibraryItemType.AUTO -> stringResource(Res.string.hidden_item_type_auto)
                                HiddenLibraryItemType.PLAYLIST -> stringResource(Res.string.playlist)
                                HiddenLibraryItemType.ALBUM -> stringResource(Res.string.album)
                                HiddenLibraryItemType.ARTIST -> stringResource(Res.string.artist)
                                HiddenLibraryItemType.PODCAST -> stringResource(Res.string.podcast)
                                HiddenLibraryItemType.OTHER -> stringResource(Res.string.hidden_item_type_other)
                            }
                        val description =
                            item.author ?: item.subtitle ?: stringResource(
                                Res.string.hidden_item_fallback,
                                typeLabel,
                                item.id,
                            )
                        custom(
                            id = "hidden:${item.type}:${item.id}",
                            title = item.title,
                            description = description,
                        ) { onSettingValueChanged ->
                            HiddenItemRow(
                                title = item.title,
                                description = description,
                                onRemove = {
                                    pendingRestore =
                                        RestoreRequest.HiddenLibrary(item.key, item.title, onSettingValueChanged)
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (SettingsPageId.BLOCKED_ARTISTS in host.availablePages) {
        val blockedArtists = BlockedContent.artists()
        page(
            id = SettingsPageId.BLOCKED_ARTISTS,
            title = stringResource(Res.string.blocked_artists),
            subtitle = stringResource(Res.string.blocked_artists_desc),
            icon = MetrolistIcons.Outlined.Block,
            tone = SettingsTone.CYAN,
        ) {
            group("blockedArtists", stringResource(Res.string.blocked_artists)) {
                if (blockedArtists.isEmpty()) {
                    action(
                        id = "empty",
                        title = stringResource(Res.string.blocked_artists_empty),
                        description = stringResource(Res.string.blocked_artists_empty_desc),
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    items(
                        remember(blockedArtists, host.onOpenArtist) {
                            blockedArtists.map { artist ->
                                SettingsItemRegistration(
                                    id = "blockedArtist:${artist.id}",
                                    title = artist.name,
                                    control = SettingsControl.CUSTOM,
                                    customContent = { onSettingValueChanged ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = artist.name,
                                                modifier =
                                                    Modifier.weight(1f).clickable {
                                                        CollectionPreviews.record(ArtistTiny(artist.id, artist.name, artist.thumbnail))
                                                        host.onOpenArtist(artist.id)
                                                    },
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            IconButton(
                                                onClick = {
                                                    pendingRestore =
                                                        RestoreRequest.BlockedArtist(artist.id, artist.name, onSettingValueChanged)
                                                },
                                                shapes = IconButtonDefaults.shapes(),
                                            ) {
                                                Icon(MetrolistIcons.Filled.Remove, stringResource(Res.string.unblock))
                                            }
                                        }
                                    },
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    pendingRestore?.let { request ->
        RestoreRequestDialog(request) { pendingRestore = null }
    }
}

private sealed interface RestoreRequest {
    val label: String
    val onRestored: () -> Unit

    data class HiddenHome(
        val key: HiddenLibraryItemKey,
        override val label: String,
        override val onRestored: () -> Unit,
    ) : RestoreRequest

    data class HiddenLibrary(
        val key: HiddenLibraryItemKey,
        override val label: String,
        override val onRestored: () -> Unit,
    ) : RestoreRequest

    data class BlockedArtist(
        val id: String,
        override val label: String,
        override val onRestored: () -> Unit,
    ) : RestoreRequest
}

@Composable
private fun HiddenItemRow(
    title: String,
    description: String?,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(MetrolistIcons.Filled.Remove, stringResource(Res.string.show_again))
        }
    }
}

@Composable
private fun RestoreRequestDialog(
    request: RestoreRequest,
    onDismiss: () -> Unit,
) {
    when (request) {
        is RestoreRequest.HiddenHome -> {
            SettingsConfirmDialog(
                title = stringResource(Res.string.restore_hidden_item_title),
                message = stringResource(Res.string.restore_hidden_item_message, request.label),
                confirmLabel = stringResource(Res.string.show_again),
                onConfirm = {
                    HiddenHomeItems.restore(request.key)
                    request.onRestored()
                },
                onDismiss = onDismiss,
            )
        }

        is RestoreRequest.HiddenLibrary -> {
            SettingsConfirmDialog(
                title = stringResource(Res.string.restore_hidden_item_title),
                message = stringResource(Res.string.restore_hidden_item_message, request.label),
                confirmLabel = stringResource(Res.string.show_again),
                onConfirm = {
                    HiddenLibraryItems.restore(request.key)
                    request.onRestored()
                },
                onDismiss = onDismiss,
            )
        }

        is RestoreRequest.BlockedArtist -> {
            SettingsConfirmDialog(
                title = stringResource(Res.string.unblock_artist_title),
                message = stringResource(Res.string.unblock_artist_message, request.label),
                confirmLabel = stringResource(Res.string.unblock),
                onConfirm = {
                    BlockedContent.unblockArtist(request.id)
                    request.onRestored()
                },
                onDismiss = onDismiss,
            )
        }
    }
}
