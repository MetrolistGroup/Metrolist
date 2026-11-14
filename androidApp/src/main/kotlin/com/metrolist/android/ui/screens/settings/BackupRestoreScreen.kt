@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens.settings

import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.sqlite.driver.AndroidSQLiteDriver
import com.metrolist.android.R
import com.metrolist.android.backup.BackupRestoreManager
import com.metrolist.android.playlistimport.AmazonMusicLoginScreen
import com.metrolist.android.playlistimport.AndroidPlaylistImportTransfer
import com.metrolist.android.playlistimport.AppleMusicLoginScreen
import com.metrolist.android.playlistimport.DeezerLoginScreen
import com.metrolist.android.playlistimport.PlaylistImportPreparationType
import com.metrolist.android.playlistimport.TidalLoginScreen
import com.metrolist.android.playlistimport.containsAndroidInaccessibleReference
import com.metrolist.android.playlistimport.readPlaylistFile
import com.metrolist.android.spotifyimport.SpotifyLoginScreen
import com.metrolist.android.spotifyimport.SpotifyPlaylistPickerSheet
import com.metrolist.android.spotifyimport.SpotifyPlaylistPreviewSheet
import com.metrolist.android.spotifyimport.androidSpotifyImportManager
import com.metrolist.android.ui.component.AdaptiveModalBottomSheet
import com.metrolist.shared.amazonmusicimport.AmazonMusicImportManager
import com.metrolist.shared.backup.defaultBackupFilename
import com.metrolist.shared.backup.foreignImportMessage
import com.metrolist.shared.backup.importForeignBackup
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.deezerimport.DeezerImportManager
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_backup
import com.metrolist.shared.generated.resources.action_restore
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.backup_create_failed
import com.metrolist.shared.generated.resources.backup_create_success
import com.metrolist.shared.generated.resources.backup_filename_prefix
import com.metrolist.shared.generated.resources.backup_restore_busy
import com.metrolist.shared.generated.resources.backup_restore_export_desc
import com.metrolist.shared.generated.resources.backup_restore_import_desc
import com.metrolist.shared.generated.resources.backup_restore_title
import com.metrolist.shared.generated.resources.backup_sensitive_message
import com.metrolist.shared.generated.resources.backup_sensitive_title
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.foreign_import_desc
import com.metrolist.shared.generated.resources.foreign_import_title
import com.metrolist.shared.generated.resources.import_action_start
import com.metrolist.shared.generated.resources.import_apple_music_account_playlists
import com.metrolist.shared.generated.resources.import_choose_single_playlist
import com.metrolist.shared.generated.resources.import_connect_amazon_music_account
import com.metrolist.shared.generated.resources.import_connect_apple_music_account
import com.metrolist.shared.generated.resources.import_connect_deezer_account
import com.metrolist.shared.generated.resources.import_connect_spotify_account
import com.metrolist.shared.generated.resources.import_connect_tidal_account
import com.metrolist.shared.generated.resources.import_destination_favorites
import com.metrolist.shared.generated.resources.import_duplicate_playlist_import_anyway
import com.metrolist.shared.generated.resources.import_duplicate_playlist_message
import com.metrolist.shared.generated.resources.import_duplicate_playlist_skip
import com.metrolist.shared.generated.resources.import_duplicate_playlist_title
import com.metrolist.shared.generated.resources.import_error_details_title
import com.metrolist.shared.generated.resources.import_error_empty_input
import com.metrolist.shared.generated.resources.import_error_empty_playlist
import com.metrolist.shared.generated.resources.import_error_file_read_failed
import com.metrolist.shared.generated.resources.import_error_generic
import com.metrolist.shared.generated.resources.import_error_incomplete_source
import com.metrolist.shared.generated.resources.import_error_local_files_require_local_playlist
import com.metrolist.shared.generated.resources.import_error_no_library_matches
import com.metrolist.shared.generated.resources.import_error_no_tracks_recognized
import com.metrolist.shared.generated.resources.import_error_no_tracks_to_import
import com.metrolist.shared.generated.resources.import_error_resolution_failed
import com.metrolist.shared.generated.resources.import_error_track_limit
import com.metrolist.shared.generated.resources.import_error_unsupported_link
import com.metrolist.shared.generated.resources.import_error_unsupported_playlist_entries
import com.metrolist.shared.generated.resources.import_error_youtube_dedicated_flow
import com.metrolist.shared.generated.resources.import_paste_amazon_hint
import com.metrolist.shared.generated.resources.import_paste_amazon_prompt
import com.metrolist.shared.generated.resources.import_paste_apple_music_hint
import com.metrolist.shared.generated.resources.import_paste_apple_music_link
import com.metrolist.shared.generated.resources.import_paste_deezer_hint
import com.metrolist.shared.generated.resources.import_paste_deezer_link
import com.metrolist.shared.generated.resources.import_paste_spotify_hint
import com.metrolist.shared.generated.resources.import_paste_spotify_link
import com.metrolist.shared.generated.resources.import_paste_tidal_hint
import com.metrolist.shared.generated.resources.import_paste_tidal_prompt
import com.metrolist.shared.generated.resources.import_paste_youtube_hint
import com.metrolist.shared.generated.resources.import_paste_youtube_link
import com.metrolist.shared.generated.resources.import_playlist
import com.metrolist.shared.generated.resources.import_playlists
import com.metrolist.shared.generated.resources.import_playlists_notice
import com.metrolist.shared.generated.resources.import_provider_amazon
import com.metrolist.shared.generated.resources.import_provider_amazon_desc
import com.metrolist.shared.generated.resources.import_provider_apple_music
import com.metrolist.shared.generated.resources.import_provider_apple_music_desc
import com.metrolist.shared.generated.resources.import_provider_deezer
import com.metrolist.shared.generated.resources.import_provider_deezer_desc
import com.metrolist.shared.generated.resources.import_provider_file
import com.metrolist.shared.generated.resources.import_provider_file_desc
import com.metrolist.shared.generated.resources.import_provider_spotify
import com.metrolist.shared.generated.resources.import_provider_spotify_desc
import com.metrolist.shared.generated.resources.import_provider_tidal
import com.metrolist.shared.generated.resources.import_provider_tidal_desc
import com.metrolist.shared.generated.resources.import_provider_youtube
import com.metrolist.shared.generated.resources.import_provider_youtube_desc
import com.metrolist.shared.generated.resources.import_review_playlists
import com.metrolist.shared.generated.resources.import_spotify_account_playlists
import com.metrolist.shared.generated.resources.imported_playlist
import com.metrolist.shared.generated.resources.liked_songs
import com.metrolist.shared.generated.resources.local_playlist
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.or_choose_existing_playlist
import com.metrolist.shared.generated.resources.playlist
import com.metrolist.shared.generated.resources.playlist_imports_title
import com.metrolist.shared.generated.resources.playlist_name
import com.metrolist.shared.generated.resources.restore_confirm_message
import com.metrolist.shared.generated.resources.restore_confirm_title
import com.metrolist.shared.generated.resources.restore_database_incompatible
import com.metrolist.shared.generated.resources.restore_error_unknown
import com.metrolist.shared.generated.resources.restore_failed
import com.metrolist.shared.generated.resources.restore_failed_detail
import com.metrolist.shared.generated.resources.restore_in_progress
import com.metrolist.shared.generated.resources.restore_stage_applying_settings
import com.metrolist.shared.generated.resources.restore_stage_converting_database
import com.metrolist.shared.generated.resources.restore_stage_installing_database
import com.metrolist.shared.generated.resources.restore_stage_reading_backup
import com.metrolist.shared.generated.resources.restore_stage_reading_settings
import com.metrolist.shared.generated.resources.restore_stage_restarting_app
import com.metrolist.shared.generated.resources.restore_stage_stopping_app
import com.metrolist.shared.generated.resources.restore_stage_validating_database
import com.metrolist.shared.generated.resources.restore_stage_verifying_database
import com.metrolist.shared.generated.resources.spotify_import_skipped_playlists
import com.metrolist.shared.generated.resources.unknown
import com.metrolist.shared.generated.resources.youtube_music
import com.metrolist.shared.playlistimport.AppleMusicPlaylistImporter
import com.metrolist.shared.playlistimport.CsvColumnMapping
import com.metrolist.shared.playlistimport.ImportErrorKey
import com.metrolist.shared.playlistimport.ImportException
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.ImportSourceType
import com.metrolist.shared.playlistimport.ImportedPlaylistData
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import com.metrolist.shared.playlistimport.UnifiedPlaylistImporter
import com.metrolist.shared.spotifyimport.SpotifyImportManager
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.tidalimport.TidalImportManager
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.ExpressiveFilterChip
import com.metrolist.shared.ui.components.PopupContent
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.menu.CsvColumnMappingDialog
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.tv.tvFocusHighlight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import meow.nyx.spotlib.PlaylistMeta
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import timber.log.Timber
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.ui.res.stringResource as androidStringResource
import com.metrolist.android.ui.component.DensityAwareDialog as Dialog
import com.metrolist.shared.ui.components.rememberModalSheetState as rememberBottomSheetState

enum class DestinationType {
    LOCAL,
    FAVORITES,
    SYNCED,
}

private data class AccountPlaylistChoice(
    val id: String,
    val title: String,
    val load: suspend ((Int, Int?) -> Unit) -> ImportedPlaylistData,
)

private data class AccountPlaylistPreparation(
    val source: String,
    val provider: ImportSourceType,
    val playlists: List<AccountPlaylistChoice>,
    val onClose: () -> Unit,
) : AutoCloseable {
    override fun close() = onClose()
}

private data class ApplePlaylistPreparation(
    val token: String,
    val playlists: List<PlaylistMeta>,
)

private suspend fun resolveImportError(throwable: Throwable?): String {
    val key = (throwable as? ImportException)?.errorKey ?: (throwable?.cause as? ImportException)?.errorKey
    return when (key) {
        ImportErrorKey.EmptyInput -> getString(Res.string.import_error_empty_input)
        ImportErrorKey.YouTubeDedicatedFlow -> getString(Res.string.import_error_youtube_dedicated_flow)
        ImportErrorKey.InvalidYouTubeUrl -> getString(Res.string.import_error_empty_playlist)
        ImportErrorKey.NoTracksRecognized -> getString(Res.string.import_error_no_tracks_recognized)
        ImportErrorKey.NoTracksToImport -> getString(Res.string.import_error_no_tracks_to_import)
        ImportErrorKey.ResolutionFailed -> getString(Res.string.import_error_resolution_failed)
        ImportErrorKey.IncompleteSource -> getString(Res.string.import_error_incomplete_source)
        ImportErrorKey.NoLibraryMatches -> getString(Res.string.import_error_no_library_matches)
        ImportErrorKey.UnsupportedPlaylistEntries -> getString(Res.string.import_error_unsupported_playlist_entries)
        ImportErrorKey.LocalFilesRequireLocalPlaylist -> getString(Res.string.import_error_local_files_require_local_playlist)
        ImportErrorKey.FileReadFailed -> getString(Res.string.import_error_file_read_failed)
        ImportErrorKey.TooManyTracks -> getString(Res.string.import_error_track_limit, PlaylistImportManager.MAX_PLAYLIST_TRACKS)
        ImportErrorKey.Generic -> getString(Res.string.import_error_generic)
        null -> throwable?.message ?: getString(Res.string.import_error_unsupported_link)
    }
}

@Composable
@Suppress("FunctionName")
fun BackupRestoreScreen(onNavigateBack: () -> Unit) {
    val importedPlaylistText = stringResource(Res.string.imported_playlist)
    val playlistText = stringResource(Res.string.playlist)
    val unknownText = stringResource(Res.string.unknown)
    val importProviderSpotifyText = stringResource(Res.string.import_provider_spotify)
    val importProviderAppleMusicText = stringResource(Res.string.import_provider_apple_music)

    val context = LocalContext.current
    val database: MusicDatabase = koinInject()
    val applicationScope: CoroutineScope = koinInject()
    val scope = rememberCoroutineScope()

    val manager =
        remember(context, database) {
            BackupRestoreManager(context.applicationContext, database)
        }
    val playlistImportManager =
        remember(database) {
            PlaylistImportManager(database, GlobalYouTubeRepository.instance)
        }
    val spotifyImportManager = androidSpotifyImportManager
    LaunchedEffect(spotifyImportManager) {
        withContext(Dispatchers.IO) { spotifyImportManager.restorePersistedSession() }
    }

    var isRestoring by remember { mutableStateOf(false) }
    var playlists by remember { mutableStateOf(emptyList<PlaylistEntity>()) }
    var playlistCollectionJob by remember { mutableStateOf<Job?>(null) }
    LaunchedEffect(database, isRestoring) {
        if (!isRestoring) {
            playlistCollectionJob = currentCoroutineContext().job
            database.dao().allPlaylists().collect { playlists = it }
        }
    }
    val editablePlaylists = remember(playlists) { playlists.filter { it.isEditable } }

    var isBusy by remember { mutableStateOf(false) }
    val activeImport by PlaylistImportStatusManager.activeImport.collectAsState()
    val controlsEnabled = !isBusy && activeImport?.isRunning != true

    var restoreUri by remember { mutableStateOf<Uri?>(null) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    var showBackupWarning by remember { mutableStateOf(false) }
    var warningSeconds by remember { mutableIntStateOf(5) }

    var showInputDialog by remember { mutableStateOf(false) }
    var inputDialogTitle by remember { mutableStateOf<String?>(null) }
    var inputDialogPrompt by remember { mutableStateOf<String?>(null) }
    var inputDialogHint by remember { mutableStateOf<String?>(null) }
    var inputDialogValue by remember { mutableStateOf("") }
    var inputDialogIsMultiline by remember { mutableStateOf(false) }
    var onInputSubmitted by remember { mutableStateOf<((String) -> Unit)?>(null) }

    var pendingImportData by remember { mutableStateOf<ImportedPlaylistData?>(null) }
    var retainedPreparationRequestId by remember { mutableStateOf<String?>(null) }
    var showDestinationPicker by remember { mutableStateOf(false) }
    var csvMapping by remember { mutableStateOf<CsvColumnMapping?>(null) }
    var returnToImportInput by remember { mutableStateOf<(() -> Unit)?>(null) }
    var destinationType by remember { mutableStateOf(DestinationType.LOCAL) }
    var selectedDestinationPlaylist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var customPlaylistTitle by remember { mutableStateOf("") }

    var errorDetails by remember { mutableStateOf<Pair<String, List<PlaylistImportManager.CsvTrack>>?>(null) }

    var duplicateWarning by remember { mutableStateOf<ImportProgressState.DuplicateDetected?>(null) }
    var pendingDuplicateDestination by remember { mutableStateOf<PlaylistImportManager.Destination?>(null) }

    var showSpotifyProviderDialog by remember { mutableStateOf(false) }
    var spotifyInputUrl by remember { mutableStateOf("") }
    var showSpotifyLogin by remember { mutableStateOf(false) }
    var isSpotifyAuthenticating by remember { mutableStateOf(false) }
    var spotifyAuthenticationError by remember { mutableStateOf<String?>(null) }
    var showSpotifyPlaylistPicker by remember { mutableStateOf(false) }
    var spotifyPlaylistMetas by remember { mutableStateOf<List<PlaylistMeta>?>(null) }
    var selectedSpotifyPlaylistUris by remember { mutableStateOf(emptySet<String>()) }
    var showSpotifyPreviewSheet by remember { mutableStateOf(false) }
    var accountPlaylists by remember { mutableStateOf<AccountPlaylistPreparation?>(null) }
    var spotifyLoadWarning by remember { mutableStateOf<String?>(null) }
    var loadedSpotifyPlaylists by remember { mutableStateOf<List<SpotifyImportManager.LoadedPlaylist>?>(null) }
    var previewSelectedUri by remember { mutableStateOf<String?>(null) }

    var showAppleMusicProviderDialog by remember { mutableStateOf(false) }
    var appleMusicInputUrl by remember { mutableStateOf("") }
    var showAppleMusicLogin by remember { mutableStateOf(false) }
    var isAppleMusicAuthenticating by remember { mutableStateOf(false) }
    var appleMusicAuthenticationError by remember { mutableStateOf<String?>(null) }
    var appleMusicUserToken by remember { mutableStateOf<String?>(null) }
    var showAppleMusicPlaylistPicker by remember { mutableStateOf(false) }
    var appleMusicPlaylistMetas by remember { mutableStateOf<List<PlaylistMeta>?>(null) }
    var selectedAppleMusicPlaylistUris by remember { mutableStateOf(emptySet<String>()) }
    var showAppleMusicPreviewSheet by remember { mutableStateOf(false) }
    var loadedAppleMusicPlaylists by remember { mutableStateOf<List<SpotifyImportManager.LoadedPlaylist>?>(null) }
    var appleMusicPreviewSelectedUri by remember { mutableStateOf<String?>(null) }

    var showDeezerProviderDialog by remember { mutableStateOf(false) }
    var deezerInputUrl by remember { mutableStateOf("") }
    var showDeezerLogin by remember { mutableStateOf(false) }
    var isDeezerAuthenticating by remember { mutableStateOf(false) }
    var deezerAuthenticationError by remember { mutableStateOf<String?>(null) }
    var showTidalProviderDialog by remember { mutableStateOf(false) }
    var tidalInputUrl by remember { mutableStateOf("") }
    var showTidalLogin by remember { mutableStateOf(false) }
    var isTidalAuthenticating by remember { mutableStateOf(false) }
    var tidalAuthenticationError by remember { mutableStateOf<String?>(null) }
    var showAmazonProviderDialog by remember { mutableStateOf(false) }
    var amazonInputUrl by remember { mutableStateOf("") }
    var showAmazonLogin by remember { mutableStateOf(false) }
    var isAmazonAuthenticating by remember { mutableStateOf(false) }
    var amazonAuthenticationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showBackupWarning) {
        if (showBackupWarning) {
            warningSeconds = 5
            while (warningSeconds > 0) {
                delay(1_000.milliseconds)
                warningSeconds--
            }
        }
    }

    val backupSuccess = stringResource(Res.string.backup_create_success)
    val backupFailed = stringResource(Res.string.backup_create_failed)
    val backupFilenamePrefix = stringResource(Res.string.backup_filename_prefix)
    val fileSource = stringResource(Res.string.import_provider_file)
    val spotifySource = stringResource(Res.string.import_provider_spotify)
    val appleMusicSource = stringResource(Res.string.import_provider_apple_music)
    val youtubeSource = stringResource(Res.string.import_provider_youtube)
    val deezerSource = stringResource(Res.string.import_provider_deezer)
    val tidalSource = stringResource(Res.string.import_provider_tidal)
    val amazonSource = stringResource(Res.string.import_provider_amazon)

    fun clearRetainedPreparationResult() {
        retainedPreparationRequestId?.let(AndroidPlaylistImportTransfer::clearPreparationResult)
        retainedPreparationRequestId = null
        returnToImportInput = null
        csvMapping = null
    }

    fun reportPreparationStartFailure(
        result: Result<String>,
        showError: Boolean = true,
        onFailure: suspend (Throwable) -> Unit = {},
    ) {
        result.exceptionOrNull()?.let { error ->
            scope.launch {
                onFailure(error)
                if (showError) errorDetails = resolveImportError(error) to emptyList()
            }
        }
    }

    val createBackupLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument("application/zip"),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    isBusy = true
                    val result = manager.backup(uri)
                    isBusy = false
                    Toast.makeText(context, if (result.isSuccess) backupSuccess else backupFailed, Toast.LENGTH_SHORT).show()
                }
            }
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                restoreUri = uri
            }
        }

    val foreignImportLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                isBusy = true
                applicationScope.launch {
                    val result =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { input ->
                                    database.importForeignBackup(input, File(context.cacheDir, "foreign-import"), AndroidSQLiteDriver())
                                } ?: error("Could not open backup")
                            }
                        }
                    val message = result.foreignImportMessage()
                    withContext(Dispatchers.Main) {
                        isBusy = false
                        Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

    val playlistFileTypes =
        arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "text/tab-separated-values",
            "text/plain",
            "application/json",
            "audio/x-mpegurl",
            "audio/mpegurl",
            "*/*",
        )

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val fullFileName =
                        withContext(Dispatchers.IO) {
                            context.getDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/')
                        }
                    val baseName =
                        fullFileName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                            ?: importedPlaylistText
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = baseName,
                            source = fileSource,
                            type = PlaylistImportPreparationType.IMPORT_DATA,
                        ) {
                            readPlaylistFile(context, uri, fullFileName.orEmpty(), baseName, fileSource).getOrThrow()
                        },
                    )
                }
            }
        }

    fun openPlaylistFile() {
        returnToImportInput = ::openPlaylistFile
        filePickerLauncher.launch(playlistFileTypes)
    }

    fun startUnifiedImport(
        data: ImportedPlaylistData,
        destination: PlaylistImportManager.Destination,
        ignoreDuplicate: Boolean = false,
    ) {
        val targetTitle = customPlaylistTitle.ifBlank { data.title }
        applicationScope.launch(Dispatchers.Main.immediate) {
            isBusy = true
            try {
                if (!ignoreDuplicate) {
                    val dup = UnifiedPlaylistImporter.checkForDuplicate(database, targetTitle, destination)
                    if (dup != null) {
                        duplicateWarning = dup
                        pendingDuplicateDestination = destination
                        return@launch
                    }
                }
                pendingImportData = null
                PlaylistImportStatusManager.activeImport.value
                    ?.id
                    ?.let(PlaylistImportStatusManager::dismiss)
                clearRetainedPreparationResult()
                AndroidPlaylistImportTransfer
                    .start(context, playlistImportManager, data, targetTitle, destination)
                    .onFailure { error ->
                        val message = resolveImportError(error)
                        errorDetails = message to emptyList()
                    }
            } finally {
                isBusy = false
            }
        }
    }

    fun openLinkOrTextInput(
        titleRes: StringResource,
        promptRes: StringResource,
        hintRes: StringResource,
        isMultiline: Boolean = false,
        onSubmit: (String) -> Unit,
    ) {
        scope.launch {
            inputDialogTitle = getString(titleRes)
            inputDialogPrompt = getString(promptRes)
            inputDialogHint = getString(hintRes)
            inputDialogIsMultiline = isMultiline
            inputDialogValue = ""
            onInputSubmitted = onSubmit
            showInputDialog = true
        }
    }

    fun loadSpotifyLibrary() {
        reportPreparationStartFailure(
            AndroidPlaylistImportTransfer.prepareForeground(
                context = context,
                playlistTitle = spotifySource,
                source = spotifySource,
                type = PlaylistImportPreparationType.SPOTIFY_PLAYLISTS,
            ) { spotifyImportManager.loadPlaylists() },
        )
    }

    fun loadAppleMusicLibrary(token: String) {
        reportPreparationStartFailure(
            AndroidPlaylistImportTransfer.prepareForeground(
                context = context,
                playlistTitle = appleMusicSource,
                source = appleMusicSource,
                type = PlaylistImportPreparationType.APPLE_PLAYLISTS,
            ) {
                ApplePlaylistPreparation(token, AppleMusicPlaylistImporter.loadUserPlaylists(token).getOrThrow())
            },
        )
    }

    fun handleLinkOrTextSubmitted(rawInput: String) {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return

        if (trimmed.contains("collection/tracks")) {
            if (spotifyImportManager.isAuthenticated) {
                loadSpotifyLibrary()
            } else {
                showSpotifyLogin = true
            }
            return
        }

        val sourceType = UnifiedPlaylistImporter.detectSource(trimmed)
        val initialSource =
            when (sourceType) {
                ImportSourceType.SPOTIFY -> spotifySource
                ImportSourceType.APPLE_MUSIC -> appleMusicSource
                ImportSourceType.YOUTUBE -> youtubeSource
                ImportSourceType.DEEZER -> deezerSource
                ImportSourceType.TIDAL -> tidalSource
                ImportSourceType.AMAZON -> amazonSource
                else -> fileSource
            }
        val guessedTitle =
            trimmed
                .substringAfterLast('/')
                .substringBefore('?')
                .take(30)
                .ifBlank { playlistText }
        reportPreparationStartFailure(
            AndroidPlaylistImportTransfer.prepareForeground(
                context = context,
                playlistTitle = guessedTitle,
                source = initialSource,
                type = PlaylistImportPreparationType.IMPORT_DATA,
            ) { update ->
                val ytId = PlaylistImportManager.parsePlaylistId(trimmed)
                if (ytId != null && (trimmed.contains("youtube.com") || trimmed.contains("youtu.be"))) {
                    update(ImportProgressState.FetchingMetadata(youtubeSource))
                    val (title, songs, coverUrl) = playlistImportManager.loadYouTubePlaylist(trimmed).getOrThrow()
                    ImportedPlaylistData(
                        title = title,
                        tracks =
                            songs.map {
                                PlaylistImportManager.CsvTrack(
                                    it.title,
                                    it.artists.joinToString { artist -> artist.name },
                                    it.id,
                                )
                            },
                        source = youtubeSource,
                        sourceYouTubePlaylistId = PlaylistImportManager.parsePlaylistId(trimmed),
                        coverUrl = coverUrl,
                    )
                } else {
                    UnifiedPlaylistImporter
                        .parseFromInput(trimmed) { loaded, total ->
                            update(ImportProgressState.LoadingTracks(initialSource, loaded, total))
                        }.getOrThrow()
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        AndroidPlaylistImportTransfer.preparationResult.collect { available ->
            if (available == null) {
                retainedPreparationRequestId = null
                return@collect
            }
            val outcome =
                AndroidPlaylistImportTransfer.currentPreparationResult(available.requestId)
                    ?: return@collect
            retainedPreparationRequestId = outcome.requestId
            isSpotifyAuthenticating = false
            isAppleMusicAuthenticating = false
            isDeezerAuthenticating = false
            isTidalAuthenticating = false
            isAmazonAuthenticating = false
            showSpotifyLogin = false
            showAppleMusicLogin = false
            showDeezerLogin = false
            showTidalLogin = false
            showAmazonLogin = false
            outcome.result.exceptionOrNull()?.let { error ->
                Timber.e(error, "Playlist import preparation failed")
                errorDetails = resolveImportError(error) to emptyList()
                return@collect
            }
            pendingImportData = null
            showDestinationPicker = false
            csvMapping = outcome.csvMapping
            when (outcome.type) {
                PlaylistImportPreparationType.IMPORT_DATA -> {
                    val data = outcome.result.getOrThrow() as ImportedPlaylistData
                    if (data.tracks.size > PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
                        errorDetails = resolveImportError(ImportException(ImportErrorKey.TooManyTracks)) to emptyList()
                    } else if (data.csv == null && data.tracks.isEmpty()) {
                        errorDetails = getString(Res.string.import_error_empty_playlist) to emptyList()
                    } else {
                        customPlaylistTitle = data.title
                        pendingImportData = data
                        showDestinationPicker = true
                    }
                }

                PlaylistImportPreparationType.ACCOUNT_PLAYLISTS -> {
                    accountPlaylists = outcome.result.getOrThrow() as AccountPlaylistPreparation
                }

                PlaylistImportPreparationType.SPOTIFY_PLAYLISTS -> {
                    @Suppress("UNCHECKED_CAST")
                    val playlists = outcome.result.getOrThrow() as List<PlaylistMeta>
                    if (spotifyPlaylistMetas !== playlists) selectedSpotifyPlaylistUris = emptySet()
                    spotifyPlaylistMetas = playlists
                    showSpotifyLogin = false
                    showSpotifyPlaylistPicker = true
                }

                PlaylistImportPreparationType.SPOTIFY_TRACKS -> {
                    val result = outcome.result.getOrThrow() as SpotifyImportManager.LoadedPlaylists
                    val loaded = result.playlists
                    if (loadedSpotifyPlaylists !== loaded) {
                        spotifyLoadWarning =
                            if (result.failedCount > 0) {
                                getPluralString(Res.plurals.spotify_import_skipped_playlists, result.failedCount, result.failedCount) +
                                    "\n\n" + result.failures.joinToString("\n") { it.playlistName }
                            } else {
                                null
                            }
                        previewSelectedUri = loaded.firstOrNull()?.uri
                    }
                    loadedSpotifyPlaylists = loaded
                    showSpotifyPreviewSheet = true
                }

                PlaylistImportPreparationType.APPLE_PLAYLISTS -> {
                    val preparation = outcome.result.getOrThrow() as ApplePlaylistPreparation
                    appleMusicUserToken = preparation.token
                    if (appleMusicPlaylistMetas !== preparation.playlists) selectedAppleMusicPlaylistUris = emptySet()
                    appleMusicPlaylistMetas = preparation.playlists
                    showAppleMusicLogin = false
                    showAppleMusicPlaylistPicker = true
                }

                PlaylistImportPreparationType.APPLE_TRACKS -> {
                    @Suppress("UNCHECKED_CAST")
                    val loaded = outcome.result.getOrThrow() as List<SpotifyImportManager.LoadedPlaylist>
                    if (loaded.sumOf { it.tracks.size } > PlaylistImportManager.MAX_PLAYLIST_TRACKS) {
                        errorDetails = resolveImportError(ImportException(ImportErrorKey.TooManyTracks)) to emptyList()
                        return@collect
                    }
                    if (loadedAppleMusicPlaylists !== loaded) appleMusicPreviewSelectedUri = loaded.firstOrNull()?.uri
                    loadedAppleMusicPlaylists = loaded
                    showAppleMusicPreviewSheet = true
                }
            }
        }
    }

    PopupVisibility(visible = showBackupWarning) {
        AlertDialog(
            onDismissRequest = { showBackupWarning = false },
            title = { Text(stringResource(Res.string.backup_sensitive_title)) },
            text = {
                Text(
                    stringResource(Res.string.backup_sensitive_message),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = warningSeconds == 0,
                    onClick = {
                        showBackupWarning = false
                        runCatching { createBackupLauncher.launch("${defaultBackupFilename(backupFilenamePrefix)}.zip") }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(
                        if (warningSeconds == 0) {
                            stringResource(Res.string.action_backup)
                        } else {
                            "${stringResource(Res.string.action_backup)} ($warningSeconds)"
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupWarning = false
                }, shapes = ButtonDefaults.shapes()) { Text(androidStringResource(android.R.string.cancel)) }
            },
        )
    }

    PopupContent(restoreUri) { uri ->
        AlertDialog(
            onDismissRequest = { restoreUri = null },
            title = { Text(stringResource(Res.string.restore_confirm_title)) },
            text = { Text(stringResource(Res.string.restore_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        restoreUri = null
                        scope.launch {
                            isBusy = true
                            isRestoring = true
                            playlistCollectionJob?.cancelAndJoin()
                            val result = manager.restore(uri)
                            isBusy = false
                            isRestoring = false
                            result.exceptionOrNull()?.let { error ->
                                restoreError = error.restoreMessage()
                            }
                        }
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.action_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreUri = null }, shapes = ButtonDefaults.shapes()) {
                    Text(androidStringResource(android.R.string.cancel))
                }
            },
        )
    }

    PopupContent(restoreError) { message ->
        AlertDialog(
            onDismissRequest = { restoreError = null },
            title = { Text(stringResource(Res.string.restore_failed)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { restoreError = null }, shapes = ButtonDefaults.shapes()) {
                    Text(androidStringResource(android.R.string.ok))
                }
            },
        )
    }

    PopupVisibility(visible = isRestoring) {
        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        ) {
            Surface(shape = MaterialTheme.shapes.extraLarge) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        stringResource(Res.string.restore_in_progress),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    if (showInputDialog && inputDialogTitle != null && onInputSubmitted != null) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        AlertDialog(
            onDismissRequest = {
                showInputDialog = false
            },
            title = { Text(inputDialogTitle.orEmpty()) },
            text = {
                Column {
                    inputDialogPrompt?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    OutlinedTextField(
                        value = inputDialogValue,
                        onValueChange = { inputDialogValue = it },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .then(if (inputDialogIsMultiline) Modifier.heightIn(min = 120.dp, max = 240.dp) else Modifier),
                        placeholder = { Text(inputDialogHint.orEmpty()) },
                        singleLine = !inputDialogIsMultiline,
                        maxLines = if (inputDialogIsMultiline) 12 else 1,
                    )
                    if (clipboard?.hasPrimaryClip() == true) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = {
                                val item = clipboard.primaryClip?.getItemAt(0)
                                val text = item?.text?.toString()
                                if (!text.isNullOrBlank()) {
                                    inputDialogValue = text
                                }
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(androidStringResource(android.R.string.paste))
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    modifier = Modifier.navigationPopupSource(),
                    enabled = inputDialogValue.isNotBlank(),
                    onClick = {
                        val callback = onInputSubmitted
                        val value = inputDialogValue
                        showInputDialog = false
                        returnToImportInput = { showInputDialog = true }
                        callback?.invoke(value)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.import_action_start))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showInputDialog = false
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(androidStringResource(android.R.string.cancel))
                }
            },
        )
    }

    PopupVisibility(visible = showSpotifyProviderDialog) {
        ProviderChoiceDialog(
            titleRes = Res.string.import_provider_spotify,
            accountActionRes =
                if (spotifyImportManager.isAuthenticated) {
                    Res.string.import_spotify_account_playlists
                } else {
                    Res.string.import_connect_spotify_account
                },
            descriptionRes =
                if (spotifyImportManager.isAuthenticated) {
                    Res.string.import_spotify_account_playlists
                } else {
                    Res.string.import_provider_spotify_desc
                },
            linkPromptRes = Res.string.import_paste_spotify_link,
            linkHintRes = Res.string.import_paste_spotify_hint,
            input = spotifyInputUrl,
            onInputChange = { spotifyInputUrl = it },
            onConnect = {
                if (spotifyImportManager.isAuthenticated) loadSpotifyLibrary() else showSpotifyLogin = true
            },
            onImportLink = { input ->
                returnToImportInput = { showSpotifyProviderDialog = true }
                handleLinkOrTextSubmitted(input)
            },
            onDismiss = { showSpotifyProviderDialog = false },
        )
    }

    PopupVisibility(visible = showAppleMusicProviderDialog) {
        ProviderChoiceDialog(
            titleRes = Res.string.import_provider_apple_music,
            accountActionRes =
                if (appleMusicUserToken != null) {
                    Res.string.import_apple_music_account_playlists
                } else {
                    Res.string.import_connect_apple_music_account
                },
            descriptionRes = Res.string.import_provider_apple_music_desc,
            linkPromptRes = Res.string.import_paste_apple_music_link,
            linkHintRes = Res.string.import_paste_apple_music_hint,
            input = appleMusicInputUrl,
            onInputChange = { appleMusicInputUrl = it },
            onConnect = {
                val token = appleMusicUserToken
                if (token != null) loadAppleMusicLibrary(token) else showAppleMusicLogin = true
            },
            onImportLink = { input ->
                returnToImportInput = { showAppleMusicProviderDialog = true }
                handleLinkOrTextSubmitted(input)
            },
            onDismiss = { showAppleMusicProviderDialog = false },
        )
    }

    pendingImportData?.let { data ->
        val csv = data.csv
        if (csv != null) {
            CsvColumnMappingDialog(
                csv = csv,
                initialMapping = csvMapping ?: csv.guessMapping(),
                onDismiss = {
                    pendingImportData = null
                    showDestinationPicker = false
                    clearRetainedPreparationResult()
                },
                onConfirm = { mapping ->
                    scope.launch {
                        val result =
                            withContext(Dispatchers.Default) {
                                runCatching {
                                    val tracks = csv.tracks(mapping)
                                    if (containsAndroidInaccessibleReference(tracks)) {
                                        throw ImportException(ImportErrorKey.UnsupportedPlaylistEntries)
                                    }
                                    if (tracks.isEmpty()) throw ImportException(ImportErrorKey.NoTracksRecognized)
                                    data.copy(tracks = tracks, csv = null)
                                }
                            }
                        result
                            .onSuccess { mapped ->
                                pendingImportData = mapped
                                AndroidPlaylistImportTransfer.replacePreparationResult(mapped, mapping)
                            }.onFailure { error ->
                                pendingImportData = null
                                showDestinationPicker = false
                                clearRetainedPreparationResult()
                                errorDetails = resolveImportError(error) to emptyList()
                            }
                    }
                },
            )
        } else if (showDestinationPicker) {
            val canManagePlaylists = GlobalYouTubeRepository.instance.canManagePlaylists()
            val onBack = {
                showDestinationPicker = false
                if (!AndroidPlaylistImportTransfer.restorePreviousPreparation()) {
                    val returnToInput = returnToImportInput
                    pendingImportData = null
                    PlaylistImportStatusManager.activeImport.value
                        ?.id
                        ?.let(PlaylistImportStatusManager::dismiss)
                    clearRetainedPreparationResult()
                    returnToInput?.invoke()
                }
            }
            AdaptiveModalBottomSheet(
                onDismissRequest = onBack,
                sheetState = rememberBottomSheetState(SheetValue.Hidden, setOf(SheetValue.Hidden, SheetValue.Expanded)),
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                dragHandle = null,
                gesturesEnabled = false,
            ) {
                Column(modifier = Modifier.fillMaxWidth().fillMaxHeight().imePadding()) {
                    Text(
                        pluralStringResource(Res.plurals.import_playlists, data.sourcePlaylistCount),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    HorizontalDivider()
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            Text(
                                pluralStringResource(
                                    Res.plurals.import_playlists_notice,
                                    data.sourcePlaylistCount,
                                    pluralStringResource(Res.plurals.n_song, data.tracks.size, data.tracks.size),
                                    data.sourcePlaylistCount,
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            OutlinedTextField(
                                value =
                                    if (destinationType == DestinationType.FAVORITES) {
                                        stringResource(Res.string.import_destination_favorites)
                                    } else {
                                        customPlaylistTitle
                                    },
                                onValueChange = { customPlaylistTitle = it },
                                label = { Text(stringResource(Res.string.playlist_name)) },
                                enabled = selectedDestinationPlaylist == null && destinationType != DestinationType.FAVORITES,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        item {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ExpressiveFilterChip(
                                    selected = selectedDestinationPlaylist == null && destinationType == DestinationType.LOCAL,
                                    onClick = {
                                        selectedDestinationPlaylist = null
                                        destinationType = DestinationType.LOCAL
                                    },
                                    label = { Text(stringResource(Res.string.local_playlist)) },
                                )
                                ExpressiveFilterChip(
                                    selected = selectedDestinationPlaylist == null && destinationType == DestinationType.FAVORITES,
                                    onClick = {
                                        selectedDestinationPlaylist = null
                                        destinationType = DestinationType.FAVORITES
                                    },
                                    label = { Text(stringResource(Res.string.import_destination_favorites)) },
                                )
                                if (canManagePlaylists) {
                                    ExpressiveFilterChip(
                                        selected = selectedDestinationPlaylist == null && destinationType == DestinationType.SYNCED,
                                        onClick = {
                                            selectedDestinationPlaylist = null
                                            destinationType = DestinationType.SYNCED
                                        },
                                        label = { Text(stringResource(Res.string.youtube_music)) },
                                    )
                                }
                            }
                        }
                        if (editablePlaylists.isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.or_choose_existing_playlist),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            items(editablePlaylists) { playlist ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = selectedDestinationPlaylist?.id == playlist.id,
                                                onClick = { selectedDestinationPlaylist = playlist },
                                                role = Role.RadioButton,
                                            ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = selectedDestinationPlaylist?.id == playlist.id,
                                        onClick = null,
                                    )
                                    Text(playlist.name, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(onClick = onBack, modifier = Modifier.tvFocusHighlight(CircleShape), shapes = ButtonDefaults.shapes()) {
                            Text(stringResource(Res.string.back))
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            modifier = Modifier.tvFocusHighlight(CircleShape),
                            enabled =

                                selectedDestinationPlaylist != null ||
                                    destinationType == DestinationType.FAVORITES ||
                                    customPlaylistTitle.isNotBlank(),
                            onClick = {
                                val destination =
                                    when {
                                        selectedDestinationPlaylist != null -> {
                                            PlaylistImportManager.Destination.EXISTING(selectedDestinationPlaylist!!)
                                        }

                                        destinationType == DestinationType.FAVORITES -> {
                                            PlaylistImportManager.Destination.Favorites
                                        }

                                        destinationType == DestinationType.SYNCED -> {
                                            PlaylistImportManager.Destination.NewSynced
                                        }

                                        else -> {
                                            PlaylistImportManager.Destination.NewLocal
                                        }
                                    }
                                showDestinationPicker = false
                                startUnifiedImport(data, destination)
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) {
                            Text(stringResource(Res.string.import_action_start))
                        }
                    }
                }
            }
        }
    }

    errorDetails?.let { (message, unimported) ->
        AlertDialog(
            onDismissRequest = {
                errorDetails = null
                clearRetainedPreparationResult()
            },
            title = { Text(stringResource(Res.string.import_error_details_title)) },
            text = {
                Column {
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                    if (unimported.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        LazyColumn(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 260.dp),
                        ) {
                            items(unimported) { track ->
                                Text(
                                    text = "• ${track.artist.ifBlank { unknownText }} -\u00a0${track.title}",
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        errorDetails = null
                        clearRetainedPreparationResult()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.close))
                }
            },
        )
    }

    PopupContent(duplicateWarning) { dup ->
        AlertDialog(
            onDismissRequest = {
                duplicateWarning = null
                pendingDuplicateDestination = null
                pendingImportData = null
                clearRetainedPreparationResult()
            },
            title = { Text(stringResource(Res.string.import_duplicate_playlist_title)) },
            text = {
                Text(
                    stringResource(Res.string.import_duplicate_playlist_message, dup.existingPlaylistName, dup.trackCount),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val dest = pendingDuplicateDestination ?: return@TextButton
                        val data = pendingImportData ?: return@TextButton
                        duplicateWarning = null
                        pendingDuplicateDestination = null
                        startUnifiedImport(data, dest, ignoreDuplicate = true)
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.import_duplicate_playlist_import_anyway))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        duplicateWarning = null
                        pendingDuplicateDestination = null
                        pendingImportData = null
                        clearRetainedPreparationResult()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.import_duplicate_playlist_skip))
                }
            },
        )
    }

    if (showSpotifyLogin) {
        SpotifyLoginScreen(
            isAuthenticating = isSpotifyAuthenticating,
            authenticationError = spotifyAuthenticationError,
            onClearAuthenticationError = { spotifyAuthenticationError = null },
            onSessionCookie = { cookie ->
                if (!isSpotifyAuthenticating) {
                    isSpotifyAuthenticating = true
                    spotifyAuthenticationError = null
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = spotifySource,
                            source = spotifySource,
                            type = PlaylistImportPreparationType.SPOTIFY_PLAYLISTS,
                        ) {
                            spotifyImportManager.authenticate(cookie)
                            spotifyImportManager.loadPlaylists()
                        },
                        showError = false,
                    ) { error ->
                        isSpotifyAuthenticating = false
                        spotifyAuthenticationError = resolveImportError(error)
                    }
                }
            },
            onNavigateBack = {
                showSpotifyLogin = false
                isSpotifyAuthenticating = false
                spotifyAuthenticationError = null
            },
        )
    }

    spotifyPlaylistMetas?.let { metas ->
        if (showSpotifyPlaylistPicker) {
            SpotifyPlaylistPickerSheet(
                playlists = metas,
                selectedUris = selectedSpotifyPlaylistUris,
                onSelectionChange = { selectedSpotifyPlaylistUris = it },
                onReview = {
                    showSpotifyPlaylistPicker = false
                    val selectedMetas = metas.filter { it.uri in selectedSpotifyPlaylistUris }
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = spotifySource,
                            source = spotifySource,
                            type = PlaylistImportPreparationType.SPOTIFY_TRACKS,
                            replaceRetainedResult = true,
                        ) { update ->
                            spotifyImportManager.loadPlaylistsForImport(
                                playlists = selectedMetas,
                                onTrackProgress = { _, _, loaded, total ->
                                    update(ImportProgressState.LoadingTracks(spotifySource, loaded, total))
                                },
                            )
                        },
                    )
                },
                onDismiss = {
                    showSpotifyPlaylistPicker = false
                    clearRetainedPreparationResult()
                },
            )
        }
    }

    PopupContent(spotifyLoadWarning) { warning ->
        AlertDialog(
            onDismissRequest = { spotifyLoadWarning = null },
            title = { Text(stringResource(Res.string.import_error_details_title)) },
            text = { Text(warning) },
            confirmButton = {
                TextButton(
                    onClick = { spotifyLoadWarning = null },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.close)) }
            },
        )
    }

    loadedSpotifyPlaylists?.let { loaded ->
        if (showSpotifyPreviewSheet && spotifyLoadWarning == null) {
            SpotifyPlaylistPreviewSheet(
                playlists = loaded,
                selectedPlaylistUri = previewSelectedUri,
                onPlaylistSelected = { previewSelectedUri = it },
                onBack = {
                    showSpotifyPreviewSheet = false
                    AndroidPlaylistImportTransfer.restorePreviousPreparation()
                },
                onContinue = {
                    showSpotifyPreviewSheet = false
                    val allTracks =
                        loaded.flatMap { pl ->
                            pl.tracks.map {
                                PlaylistImportManager.CsvTrack(
                                    it.title,
                                    it.artist,
                                    null,
                                    album = it.album,
                                    durationSeconds = it.durationSeconds,
                                )
                            }
                        }
                    val spotifyDefaultName = importProviderSpotifyText
                    val name = if (loaded.size == 1) loaded.first().name else spotifyDefaultName
                    customPlaylistTitle = name
                    val data =
                        ImportedPlaylistData(
                            title = name,
                            tracks = allTracks,
                            source = spotifySource,
                            provider = ImportSourceType.SPOTIFY,
                            sourcePlaylistCount = loaded.size,
                        )
                    pendingImportData = data
                    AndroidPlaylistImportTransfer.replacePreparationResult(data)
                    showDestinationPicker = true
                },
            )
        }
    }

    if (showAppleMusicLogin) {
        AppleMusicLoginScreen(
            isAuthenticating = isAppleMusicAuthenticating,
            authenticationError = appleMusicAuthenticationError,
            onClearAuthenticationError = { appleMusicAuthenticationError = null },
            onSessionToken = { token ->
                showAppleMusicLogin = false
                loadAppleMusicLibrary(token)
            },
            onNavigateBack = {
                showAppleMusicLogin = false
                isAppleMusicAuthenticating = false
                appleMusicAuthenticationError = null
            },
        )
    }

    appleMusicPlaylistMetas?.let { metas ->
        if (showAppleMusicPlaylistPicker) {
            SpotifyPlaylistPickerSheet(
                title = stringResource(Res.string.import_provider_apple_music),
                playlists = metas,
                selectedUris = selectedAppleMusicPlaylistUris,
                onSelectionChange = { selectedAppleMusicPlaylistUris = it },
                onReview = {
                    showAppleMusicPlaylistPicker = false
                    val token = appleMusicUserToken ?: return@SpotifyPlaylistPickerSheet
                    val selectedMetas = metas.filter { it.uri in selectedAppleMusicPlaylistUris }
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = appleMusicSource,
                            source = appleMusicSource,
                            type = PlaylistImportPreparationType.APPLE_TRACKS,
                            replaceRetainedResult = true,
                        ) { update ->
                            selectedMetas.mapIndexed { index, meta ->
                                update(ImportProgressState.LoadingTracks(appleMusicSource, index + 1, selectedMetas.size))
                                val importedData =
                                    AppleMusicPlaylistImporter
                                        .loadUserPlaylistTracks(
                                            userToken = token,
                                            playlistUriOrId = meta.uri,
                                            playlistName = meta.name,
                                            onProgress = { current, total ->
                                                update(
                                                    ImportProgressState.LoadingTracks(
                                                        appleMusicSource,
                                                        current,
                                                        total,
                                                    ),
                                                )
                                            },
                                        ).getOrThrow()
                                SpotifyImportManager.LoadedPlaylist(
                                    uri = meta.uri,
                                    name = meta.name,
                                    image = meta.image,
                                    tracks =
                                        importedData.tracks.map {
                                            SpotifyImportManager.ImportTrack(
                                                it.title,
                                                it.artist,
                                                it.album,
                                                it.durationSeconds,
                                            )
                                        },
                                    reachedTrackLimit = false,
                                )
                            }
                        },
                    )
                },
                onDismiss = {
                    showAppleMusicPlaylistPicker = false
                    clearRetainedPreparationResult()
                },
            )
        }
    }

    loadedAppleMusicPlaylists?.let { loaded ->
        if (showAppleMusicPreviewSheet) {
            val appleMusicDefaultName = importProviderAppleMusicText
            SpotifyPlaylistPreviewSheet(
                title = pluralStringResource(Res.plurals.import_review_playlists, loaded.size, appleMusicDefaultName),
                playlists = loaded,
                selectedPlaylistUri = appleMusicPreviewSelectedUri,
                onPlaylistSelected = { appleMusicPreviewSelectedUri = it },
                onBack = {
                    showAppleMusicPreviewSheet = false
                    AndroidPlaylistImportTransfer.restorePreviousPreparation()
                },
                onContinue = {
                    showAppleMusicPreviewSheet = false
                    val allTracks =
                        loaded.flatMap { pl ->
                            pl.tracks.map {
                                PlaylistImportManager.CsvTrack(
                                    it.title,
                                    it.artist,
                                    null,
                                    album = it.album,
                                    durationSeconds = it.durationSeconds,
                                )
                            }
                        }
                    val name = if (loaded.size == 1) loaded.first().name else appleMusicDefaultName
                    customPlaylistTitle = name
                    val data =
                        ImportedPlaylistData(
                            title = name,
                            tracks = allTracks,
                            source = appleMusicSource,
                            provider = ImportSourceType.APPLE_MUSIC,
                            sourcePlaylistCount = loaded.size,
                        )
                    pendingImportData = data
                    AndroidPlaylistImportTransfer.replacePreparationResult(data)
                    showDestinationPicker = true
                },
            )
        }
    }

    PopupContent(accountPlaylists) { preparation ->
        AlertDialog(
            onDismissRequest = {
                accountPlaylists = null
                clearRetainedPreparationResult()
            },
            title = { Text(preparation.source) },
            text = {
                LazyColumn {
                    item { Text(stringResource(Res.string.import_choose_single_playlist)) }
                    items(preparation.playlists, key = { it.id }) { playlist ->
                        TextButton(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                reportPreparationStartFailure(
                                    AndroidPlaylistImportTransfer
                                        .prepareForeground(
                                            context = context,
                                            playlistTitle = playlist.title,
                                            source = preparation.source,
                                            type = PlaylistImportPreparationType.IMPORT_DATA,
                                            replaceRetainedResult = true,
                                        ) { update ->
                                            playlist
                                                .load { loaded, total ->
                                                    update(ImportProgressState.LoadingTracks(preparation.source, loaded, total))
                                                }.copy(provider = preparation.provider)
                                        },
                                )
                                accountPlaylists = null
                            },
                            shapes = ButtonDefaults.shapes(),
                        ) { Text(playlist.title) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    accountPlaylists = null
                    clearRetainedPreparationResult()
                }, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.close)) }
            },
        )
    }

    PopupVisibility(visible = showDeezerProviderDialog) {
        ProviderChoiceDialog(
            titleRes = Res.string.import_provider_deezer,
            accountActionRes = Res.string.import_connect_deezer_account,
            descriptionRes = Res.string.import_provider_deezer_desc,
            linkPromptRes = Res.string.import_paste_deezer_link,
            linkHintRes = Res.string.import_paste_deezer_hint,
            input = deezerInputUrl,
            onInputChange = { deezerInputUrl = it },
            onConnect = { showDeezerLogin = true },
            onImportLink = { input ->
                returnToImportInput = { showDeezerProviderDialog = true }
                handleLinkOrTextSubmitted(input)
            },
            onDismiss = { showDeezerProviderDialog = false },
        )
    }

    if (showDeezerLogin) {
        DeezerLoginScreen(
            isAuthenticating = isDeezerAuthenticating,
            authenticationError = deezerAuthenticationError,
            onClearAuthenticationError = { deezerAuthenticationError = null },
            onSessionToken = { arl ->
                if (!isDeezerAuthenticating) {
                    isDeezerAuthenticating = true
                    deezerAuthenticationError = null
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = deezerSource,
                            source = deezerSource,
                            type = PlaylistImportPreparationType.ACCOUNT_PLAYLISTS,
                        ) { _ ->
                            val manager = DeezerImportManager()
                            try {
                                manager.authenticate(arl)
                                val likedSongsName = getString(Res.string.liked_songs)
                                val playlists = manager.loadPlaylists(likedSongsName = likedSongsName)
                                AccountPlaylistPreparation(
                                    source = deezerSource,
                                    provider = ImportSourceType.DEEZER,
                                    playlists =
                                        playlists.map { playlist ->
                                            AccountPlaylistChoice(playlist.id, playlist.name) { progress ->
                                                manager.loadPlaylist(playlist, likedSongsName, progress)
                                            }
                                        },
                                    onClose = manager::close,
                                )
                            } catch (error: Throwable) {
                                manager.close()
                                throw error
                            }
                        },
                        showError = false,
                    ) { error ->
                        isDeezerAuthenticating = false
                        deezerAuthenticationError = resolveImportError(error)
                    }
                }
            },
            onNavigateBack = {
                showDeezerLogin = false
                isDeezerAuthenticating = false
                deezerAuthenticationError = null
            },
        )
    }

    PopupVisibility(visible = showTidalProviderDialog) {
        ProviderChoiceDialog(
            titleRes = Res.string.import_provider_tidal,
            accountActionRes = Res.string.import_connect_tidal_account,
            descriptionRes = Res.string.import_provider_tidal_desc,
            linkPromptRes = Res.string.import_paste_tidal_prompt,
            linkHintRes = Res.string.import_paste_tidal_hint,
            input = tidalInputUrl,
            onInputChange = { tidalInputUrl = it },
            onConnect = { showTidalLogin = true },
            onImportLink = { input ->
                returnToImportInput = { showTidalProviderDialog = true }
                handleLinkOrTextSubmitted(input)
            },
            onDismiss = { showTidalProviderDialog = false },
        )
    }

    if (showTidalLogin) {
        TidalLoginScreen(
            isAuthenticating = isTidalAuthenticating,
            authenticationError = tidalAuthenticationError,
            onClearAuthenticationError = { tidalAuthenticationError = null },
            onSessionAuthenticated = { sid, uid ->
                if (!isTidalAuthenticating) {
                    isTidalAuthenticating = true
                    tidalAuthenticationError = null
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = tidalSource,
                            source = tidalSource,
                            type = PlaylistImportPreparationType.ACCOUNT_PLAYLISTS,
                        ) { _ ->
                            val manager = TidalImportManager()
                            try {
                                manager.authenticate(sid, uid)
                                val likedSongsName = getString(Res.string.liked_songs)
                                val playlists = manager.loadPlaylists(likedSongsName = likedSongsName)
                                AccountPlaylistPreparation(
                                    source = tidalSource,
                                    provider = ImportSourceType.TIDAL,
                                    playlists =
                                        playlists.map { playlist ->
                                            AccountPlaylistChoice(playlist.id, playlist.name) { progress ->
                                                manager.loadPlaylist(playlist, likedSongsName, progress)
                                            }
                                        },
                                    onClose = manager::close,
                                )
                            } catch (error: Throwable) {
                                manager.close()
                                throw error
                            }
                        },
                        showError = false,
                    ) { error ->
                        isTidalAuthenticating = false
                        tidalAuthenticationError = resolveImportError(error)
                    }
                }
            },
            onNavigateBack = {
                showTidalLogin = false
                isTidalAuthenticating = false
                tidalAuthenticationError = null
            },
        )
    }

    PopupVisibility(visible = showAmazonProviderDialog) {
        ProviderChoiceDialog(
            titleRes = Res.string.import_provider_amazon,
            accountActionRes = Res.string.import_connect_amazon_music_account,
            descriptionRes = Res.string.import_provider_amazon_desc,
            linkPromptRes = Res.string.import_paste_amazon_prompt,
            linkHintRes = Res.string.import_paste_amazon_hint,
            input = amazonInputUrl,
            onInputChange = { amazonInputUrl = it },
            onConnect = { showAmazonLogin = true },
            onImportLink = { input ->
                returnToImportInput = { showAmazonProviderDialog = true }
                handleLinkOrTextSubmitted(input)
            },
            onDismiss = { showAmazonProviderDialog = false },
        )
    }

    if (showAmazonLogin) {
        AmazonMusicLoginScreen(
            isAuthenticating = isAmazonAuthenticating,
            authenticationError = amazonAuthenticationError,
            onClearAuthenticationError = { amazonAuthenticationError = null },
            onSessionToken = { cookies, host ->
                if (!isAmazonAuthenticating) {
                    isAmazonAuthenticating = true
                    amazonAuthenticationError = null
                    reportPreparationStartFailure(
                        AndroidPlaylistImportTransfer.prepareForeground(
                            context = context,
                            playlistTitle = amazonSource,
                            source = amazonSource,
                            type = PlaylistImportPreparationType.ACCOUNT_PLAYLISTS,
                        ) { _ ->
                            val manager = AmazonMusicImportManager()
                            try {
                                manager.authenticate(cookies, host)
                                val likedSongsName = getString(Res.string.liked_songs)
                                val playlists = manager.loadPlaylists(likedSongsName = likedSongsName)
                                AccountPlaylistPreparation(
                                    source = amazonSource,
                                    provider = ImportSourceType.AMAZON,
                                    playlists =
                                        playlists.map { playlist ->
                                            AccountPlaylistChoice(playlist.id, playlist.name) { progress ->
                                                manager.loadPlaylist(playlist, likedSongsName, progress)
                                            }
                                        },
                                    onClose = manager::close,
                                )
                            } catch (error: Throwable) {
                                manager.close()
                                throw error
                            }
                        },
                        showError = false,
                    ) { error ->
                        isAmazonAuthenticating = false
                        amazonAuthenticationError = resolveImportError(error)
                    }
                }
            },
            onNavigateBack = {
                showAmazonLogin = false
                isAmazonAuthenticating = false
                amazonAuthenticationError = null
            },
        )
    }

    SettingsSubScreen(
        title = stringResource(Res.string.backup_restore_title),
        onNavigateBack = onNavigateBack,
    ) {
        if (isBusy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
        SettingsSubScreenGroup(
            title = stringResource(Res.string.backup_restore_title),
            items =
                listOf(
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.action_backup),
                        description =
                            if (isBusy) {
                                stringResource(
                                    Res.string.backup_restore_busy,
                                )
                            } else {
                                stringResource(Res.string.backup_restore_export_desc)
                            },
                        enabled = controlsEnabled,
                        onClick = { showBackupWarning = true },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.action_restore),
                        description =
                            if (isBusy) {
                                stringResource(
                                    Res.string.backup_restore_busy,
                                )
                            } else {
                                stringResource(Res.string.backup_restore_import_desc)
                            },
                        enabled = controlsEnabled,
                        onClick = {
                            runCatching {
                                restoreLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*"),
                                )
                            }
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.foreign_import_title),
                        description =
                            if (isBusy) {
                                stringResource(Res.string.backup_restore_busy)
                            } else {
                                stringResource(Res.string.foreign_import_desc)
                            },
                        enabled = controlsEnabled,
                        onClick = {
                            runCatching {
                                foreignImportLauncher.launch(
                                    arrayOf("application/zip", "application/octet-stream", "*/*"),
                                )
                            }
                        },
                    ),
                ),
        )

        SettingsSubScreenGroup(
            title = stringResource(Res.string.playlist_imports_title),
            items =
                listOf(
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_spotify),
                        description = stringResource(Res.string.import_provider_spotify_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            spotifyInputUrl = ""
                            showSpotifyProviderDialog = true
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_apple_music),
                        description = stringResource(Res.string.import_provider_apple_music_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            appleMusicInputUrl = ""
                            showAppleMusicProviderDialog = true
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_youtube),
                        description = stringResource(Res.string.import_provider_youtube_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            openLinkOrTextInput(
                                titleRes = Res.string.import_provider_youtube,
                                promptRes = Res.string.import_paste_youtube_link,
                                hintRes = Res.string.import_paste_youtube_hint,
                                isMultiline = false,
                                onSubmit = ::handleLinkOrTextSubmitted,
                            )
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_deezer),
                        description = stringResource(Res.string.import_provider_deezer_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            deezerInputUrl = ""
                            showDeezerProviderDialog = true
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_tidal),
                        description = stringResource(Res.string.import_provider_tidal_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            tidalInputUrl = ""
                            showTidalProviderDialog = true
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_amazon),
                        description = stringResource(Res.string.import_provider_amazon_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            amazonInputUrl = ""
                            showAmazonProviderDialog = true
                        },
                    ),
                    SettingsSubScreenItem(
                        title = stringResource(Res.string.import_provider_file),
                        description = stringResource(Res.string.import_provider_file_desc),
                        enabled = controlsEnabled,
                        onClick = {
                            openPlaylistFile()
                        },
                    ),
                ),
        )
    }
}

private suspend fun Throwable.restoreMessage(): String =
    when (this) {
        is BackupRestoreManager.IncompatibleBackupException -> {
            getString(
                Res.string.restore_database_incompatible,
                backupVersion,
                currentVersion,
            )
        }

        is BackupRestoreManager.RestoreFailedException -> {
            getString(
                Res.string.restore_failed_detail,
                getString(stage.descriptionResource),
                cause?.message?.takeIf(String::isNotBlank) ?: getString(Res.string.restore_error_unknown),
            )
        }

        else -> {
            getString(
                Res.string.restore_failed_detail,
                getString(Res.string.restore_stage_reading_backup),
                message?.takeIf(String::isNotBlank) ?: getString(Res.string.restore_error_unknown),
            )
        }
    }

private val BackupRestoreManager.RestoreStage.descriptionResource: StringResource
    get() =
        when (this) {
            BackupRestoreManager.RestoreStage.READING_BACKUP -> Res.string.restore_stage_reading_backup
            BackupRestoreManager.RestoreStage.READING_SETTINGS -> Res.string.restore_stage_reading_settings
            BackupRestoreManager.RestoreStage.VALIDATING_DATABASE -> Res.string.restore_stage_validating_database
            BackupRestoreManager.RestoreStage.CONVERTING_DATABASE -> Res.string.restore_stage_converting_database
            BackupRestoreManager.RestoreStage.STOPPING_APP -> Res.string.restore_stage_stopping_app
            BackupRestoreManager.RestoreStage.INSTALLING_DATABASE -> Res.string.restore_stage_installing_database
            BackupRestoreManager.RestoreStage.VERIFYING_DATABASE -> Res.string.restore_stage_verifying_database
            BackupRestoreManager.RestoreStage.APPLYING_SETTINGS -> Res.string.restore_stage_applying_settings
            BackupRestoreManager.RestoreStage.RESTARTING_APP -> Res.string.restore_stage_restarting_app
        }

private fun Context.getDisplayName(uri: Uri): String? {
    if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
        runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) cursor.getString(index) else cursor.getString(0)
                } else {
                    null
                }
            }
        }.getOrNull()?.let { return it }
    }
    return uri.lastPathSegment?.substringAfterLast('/')
}
