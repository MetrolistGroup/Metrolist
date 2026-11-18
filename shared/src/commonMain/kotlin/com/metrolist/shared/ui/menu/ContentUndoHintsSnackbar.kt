package com.metrolist.shared.ui.menu

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.blocked_artists
import com.metrolist.shared.generated.resources.blocked_songs
import com.metrolist.shared.generated.resources.content_undo_hint
import com.metrolist.shared.generated.resources.content_undo_path
import com.metrolist.shared.generated.resources.hidden_home_items
import com.metrolist.shared.generated.resources.hidden_playlists
import com.metrolist.shared.generated.resources.listen_together
import com.metrolist.shared.generated.resources.listen_together_blocked_users
import com.metrolist.shared.generated.resources.privacy_settings_title
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.shortcut_open_settings
import com.metrolist.shared.settings.ContentUndoHints
import com.metrolist.shared.settings.ContentUndoPage
import com.metrolist.shared.ui.settings.SettingsPageId
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.getString

val ContentUndoPage.settingsPage: SettingsPageId?
    get() =
        when (this) {
            ContentUndoPage.HIDDEN_LIBRARY_ITEMS -> SettingsPageId.HIDDEN_LIBRARY_ITEMS
            ContentUndoPage.HIDDEN_HOME_ITEMS -> SettingsPageId.HIDDEN_HOME_ITEMS
            ContentUndoPage.BLOCKED_ARTISTS -> SettingsPageId.BLOCKED_ARTISTS
            ContentUndoPage.BLOCKED_SONGS -> SettingsPageId.BLOCKED_SONGS
            ContentUndoPage.BLOCKED_USERS -> null
        }

@Composable
fun ContentUndoHintsSnackbar(
    snackbarHostState: SnackbarHostState,
    onOpen: (ContentUndoPage) -> Unit,
) {
    val currentOnOpen = rememberUpdatedState(onOpen)
    val currentHint = snackbarHostState.currentSnackbarData?.visuals as? ContentUndoHintVisuals
    LaunchedEffect(currentHint) {
        currentHint?.let { ContentUndoHints.markShown(it.page) }
    }
    LaunchedEffect(snackbarHostState) {
        ContentUndoHints.pending.map { it.firstOrNull() }.distinctUntilChanged().collect { page ->
            if (page == null) return@collect
            val title =
                getString(
                    when (page) {
                        ContentUndoPage.HIDDEN_LIBRARY_ITEMS -> Res.string.hidden_playlists
                        ContentUndoPage.HIDDEN_HOME_ITEMS -> Res.string.hidden_home_items
                        ContentUndoPage.BLOCKED_ARTISTS -> Res.string.blocked_artists
                        ContentUndoPage.BLOCKED_SONGS -> Res.string.blocked_songs
                        ContentUndoPage.BLOCKED_USERS -> Res.string.listen_together_blocked_users
                    },
                )
            val path =
                getString(
                    Res.string.content_undo_path,
                    getString(if (page == ContentUndoPage.BLOCKED_USERS) Res.string.listen_together else Res.string.settings),
                    getString(if (page == ContentUndoPage.BLOCKED_USERS) Res.string.settings else Res.string.privacy_settings_title),
                    title,
                )
            val result =
                snackbarHostState.showSnackbar(
                    ContentUndoHintVisuals(
                        page = page,
                        message = getString(Res.string.content_undo_hint, path),
                        actionLabel = getString(Res.string.shortcut_open_settings),
                    ),
                )
            ContentUndoHints.markShown(page)
            if (result == SnackbarResult.ActionPerformed) currentOnOpen.value(page)
        }
    }
}

internal data class ContentUndoHintVisuals(
    val page: ContentUndoPage,
    override val message: String,
    override val actionLabel: String,
) : SnackbarVisuals {
    override val duration = SnackbarDuration.Long
    override val withDismissAction = true
}
