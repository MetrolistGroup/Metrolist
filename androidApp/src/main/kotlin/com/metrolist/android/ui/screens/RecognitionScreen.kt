@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonColors
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.withResumed
import coil3.compose.AsyncImage
import com.metrolist.android.LocalPlaylistAdder
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.recognition.MusicRecognitionService
import com.metrolist.android.recognition.RecognitionWidgetService
import com.metrolist.android.ui.screens.settings.SettingsSubScreen
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.RecognitionHistory
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.action_like
import com.metrolist.shared.generated.resources.add_to_playlist
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.clear_search
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.delete
import com.metrolist.shared.generated.resources.device_has_no_microphone
import com.metrolist.shared.generated.resources.last_week
import com.metrolist.shared.generated.resources.liked
import com.metrolist.shared.generated.resources.listening
import com.metrolist.shared.generated.resources.microphone_unavailable
import com.metrolist.shared.generated.resources.no_match_found
import com.metrolist.shared.generated.resources.no_matches
import com.metrolist.shared.generated.resources.no_recognized_songs_hint
import com.metrolist.shared.generated.resources.no_recognized_songs_yet
import com.metrolist.shared.generated.resources.play_on_app
import com.metrolist.shared.generated.resources.processing
import com.metrolist.shared.generated.resources.re_listen
import com.metrolist.shared.generated.resources.recognition
import com.metrolist.shared.generated.resources.recognition_error
import com.metrolist.shared.generated.resources.recognition_history
import com.metrolist.shared.generated.resources.recognition_history_earlier
import com.metrolist.shared.generated.resources.recognition_history_save_failed
import com.metrolist.shared.generated.resources.recognition_listening_hint
import com.metrolist.shared.generated.resources.recognition_match_found
import com.metrolist.shared.generated.resources.recognition_microphone_permission_description
import com.metrolist.shared.generated.resources.recognition_microphone_permission_not_granted
import com.metrolist.shared.generated.resources.recognition_no_match_message
import com.metrolist.shared.generated.resources.recognize
import com.metrolist.shared.generated.resources.search_recognized_songs
import com.metrolist.shared.generated.resources.shortcut_open_settings
import com.metrolist.shared.generated.resources.tap_to_recognize
import com.metrolist.shared.generated.resources.this_week
import com.metrolist.shared.generated.resources.today
import com.metrolist.shared.generated.resources.try_again
import com.metrolist.shared.generated.resources.yesterday
import com.metrolist.shared.model.HistoryDateBucket
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.historyDateBucket
import com.metrolist.shared.recognition.RecognitionResult
import com.metrolist.shared.recognition.RecognitionStatus
import com.metrolist.shared.recognition.toSongItem
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.HistoryEntryGroupPosition
import com.metrolist.shared.ui.components.sliders.rememberWavePhase
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalContentObscured
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.theme.ComponentShapes
import com.metrolist.shared.ui.tv.tvFocusHighlight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.time.Clock
import android.provider.Settings as AndroidSettings

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RecognitionScreen(
    onNavigateBack: () -> Unit,
    onNavigateToHistory: () -> Unit,
) {
    val context = LocalContext.current
    val database: MusicDatabase = koinInject()
    val playerConnection = LocalPlayerConnection.current
    val scope = rememberCoroutineScope()
    val isTv = LocalIsTv.current
    val hasMicrophone =
        remember(context) {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        }
    val status by MusicRecognitionService.status.collectAsState()
    var hasPermission by remember { mutableStateOf(MusicRecognitionService.hasRecordPermission(context)) }
    var showPermissionDialog by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            hasPermission = MusicRecognitionService.hasRecordPermission(context)
            if (hasPermission) showPermissionDialog = false
        }
    }
    val addToPlaylist = LocalPlaylistAdder.current
    var recognitionRequested by remember { mutableStateOf(false) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
            showPermissionDialog = !granted
            if (granted) recognitionRequested = true
        }

    LaunchedEffect(recognitionRequested, lifecycle) {
        if (recognitionRequested) {
            lifecycle.withResumed {
                recognitionRequested = false
                if (MusicRecognitionService.hasRecordPermission(context)) {
                    RecognitionWidgetService.start(context)
                } else {
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    fun startRecognition() {
        if (hasMicrophone) recognitionRequested = true
    }

    if (showPermissionDialog) {
        val canRequestAgain =
            activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } == true
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text(stringResource(Res.string.recognition_microphone_permission_not_granted)) },
            text = { Text(stringResource(Res.string.recognition_microphone_permission_description)) },
            confirmButton = {
                Button(onClick = {
                    showPermissionDialog = false
                    if (canRequestAgain) {
                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()),
                        )
                    }
                }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(if (canRequestAgain) Res.string.try_again else Res.string.shortcut_open_settings))
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionDialog = false }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    LaunchedEffect(Unit) {
        if (!isTv && hasMicrophone && status is RecognitionStatus.Ready) startRecognition()
    }

    BackHandler(enabled = status !is RecognitionStatus.Ready) {
        if (status is RecognitionStatus.Listening || status is RecognitionStatus.Processing) {
            RecognitionWidgetService.stop(context)
        } else {
            MusicRecognitionService.reset()
        }
    }

    val screenHeight =
        with(LocalDensity.current) {
            LocalWindowInfo.current.containerSize.height
                .toDp()
        }
    val chromeAboveContent = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 64.dp + 16.dp
    val topEmptySpace = (screenHeight / 2 - chromeAboveContent - HeroBlobCenterOffset).coerceAtLeast(0.dp)

    SettingsSubScreen(
        title = stringResource(Res.string.recognition),
        onNavigateBack = onNavigateBack,
        actions = {
            IconButton(onClick = onNavigateToHistory, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.History,
                    contentDescription = stringResource(Res.string.recognition_history),
                )
            }
        },
        contentScrollable = false,
        compactTopBar = true,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(top = topEmptySpace)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            RecognitionHero(
                kind = status.heroKind(hasMicrophone),
                isListening = status is RecognitionStatus.Listening,
                onCapture = {
                    if (status is RecognitionStatus.Listening) RecognitionWidgetService.stop(context) else startRecognition()
                },
            )
            AnimatedContent(
                targetState = status,
                // Keep one composable across Ready and Listening so the blob animation continues.
                contentKey = { target ->
                    when (target) {
                        RecognitionStatus.Ready, RecognitionStatus.Listening -> "capture"
                        RecognitionStatus.Processing -> "processing"
                        is RecognitionStatus.NoMatch -> "no_match"
                        is RecognitionStatus.Error -> "error"
                        is RecognitionStatus.Success -> "success"
                    }
                },
                transitionSpec = {
                    (
                        fadeIn(tween(220, delayMillis = 60)) +
                            scaleIn(tween(220, delayMillis = 60), initialScale = 0.94f)
                    ).togetherWith(
                        fadeOut(tween(100)) + scaleOut(tween(120), targetScale = 0.94f),
                    ).using(SizeTransform(clip = false))
                },
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                label = "recognition_status",
            ) { current ->
                when (current) {
                    RecognitionStatus.Ready, RecognitionStatus.Listening -> {
                        if (hasMicrophone) {
                            RecognitionCaptureFooter(
                                isListening = current is RecognitionStatus.Listening,
                                onCancel = { RecognitionWidgetService.stop(context) },
                            )
                        } else {
                            RecognitionMessageContent(
                                title = stringResource(Res.string.microphone_unavailable),
                                message = stringResource(Res.string.device_has_no_microphone),
                                onRetry = null,
                            )
                        }
                    }

                    RecognitionStatus.Processing -> {
                        RecognitionProcessingFooter()
                    }

                    is RecognitionStatus.NoMatch -> {
                        RecognitionNoMatchContent(message = current.message, onTryAgain = ::startRecognition)
                    }

                    is RecognitionStatus.Error -> {
                        RecognitionMessageContent(
                            title = stringResource(Res.string.recognition_error),
                            message = current.message,
                            onRetry = ::startRecognition,
                        )
                    }

                    is RecognitionStatus.Success -> {
                        val effectiveResult = current.result
                        val song = effectiveResult.toSongItem()
                        val isLiked = song != null && SongLikeState.isLiked(song.id)
                        RecognitionSuccessState(
                            result = effectiveResult,
                            historySaved = current.historySaved,
                            song = song,
                            isLiked = isLiked,
                            onPlay = { song?.let { playerConnection?.playSong(it, "") } },
                            onRetry = ::startRecognition,
                            onClose = { MusicRecognitionService.reset() },
                            onLike = {
                                song?.let { s ->
                                    val liked = SongLikeState.toggleLike(s)
                                    scope.launch(Dispatchers.IO) {
                                        val trackId =
                                            effectiveResult.trackId.ifBlank {
                                                "${effectiveResult.title}:${effectiveResult.artist}"
                                            }
                                        database.dao().updateRecognitionHistoryLikedByTrack(trackId, liked)
                                    }
                                }
                            },
                            onAddToPlaylist = { song?.let { addToPlaylist(listOf(it)) } },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecognitionHistoryContent(
    history: List<RecognitionHistory>,
    query: String,
    onQueryChange: (String) -> Unit,
    onPlay: (RecognitionHistory) -> Unit,
    onDelete: (RecognitionHistory) -> Unit,
) {
    val textFieldState = rememberTextFieldState(initialText = query)
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val latestQuery by rememberUpdatedState(query)
    val latestOnQueryChange by rememberUpdatedState(onQueryChange)

    LaunchedEffect(query) {
        if (textFieldState.text.toString() != query) {
            textFieldState.setTextAndPlaceCursorAtEnd(query)
        }
    }

    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }
            .collect { text ->
                if (text != latestQuery) {
                    latestOnQueryChange(text)
                }
            }
    }

    val searchBarColors =
        SearchBarDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    var nowMs by remember { mutableLongStateOf(Clock.System.now().toEpochMilliseconds()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                nowMs = Clock.System.now().toEpochMilliseconds()
                val zone = ZoneId.systemDefault()
                val nextDay =
                    Instant
                        .ofEpochMilli(
                            nowMs,
                        ).atZone(zone)
                        .toLocalDate()
                        .plusDays(1)
                        .atStartOfDay(zone)
                        .toInstant()
                        .toEpochMilli()
                delay((nextDay - nowMs).coerceAtLeast(1))
            }
        }
    }
    val grouped =
        remember(history, nowMs) {
            history.groupBy { entry ->
                historyDateBucket(entry.recognizedAt.toInstant(TimeZone.UTC).toEpochMilliseconds(), nowMs)
            }
        }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.weight(1f).height(56.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 0.dp,
            ) {
                SearchBarDefaults.InputField(
                    textFieldState = textFieldState,
                    searchBarState = searchBarState,
                    onSearch = {},
                    modifier = Modifier.fillMaxSize(),
                    placeholder = {
                        Text(stringResource(Res.string.search_recognized_songs))
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = MetrolistIcons.Default.Search,
                            contentDescription = null,
                        )
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { onQueryChange("") }, shapes = IconButtonDefaults.shapes()) {
                                Icon(
                                    imageVector = MetrolistIcons.Default.Clear,
                                    contentDescription = stringResource(Res.string.clear_search),
                                )
                            }
                        }
                    },
                    colors = searchBarColors.inputFieldColors,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (history.isEmpty()) {
            RecognitionHistoryEmptyState(isSearching = query.isNotBlank())
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                grouped.forEach { (bucket, entries) ->
                    item(key = "header_${bucket.name}") {
                        Text(
                            text = stringResource(bucket.labelRes()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(entries, key = { _, entry -> entry.id }) { index, entry ->
                        RecognitionHistoryRow(
                            entry = entry,
                            groupPosition =
                                when {
                                    entries.size == 1 -> HistoryEntryGroupPosition.SINGLE
                                    index == 0 -> HistoryEntryGroupPosition.FIRST
                                    index == entries.lastIndex -> HistoryEntryGroupPosition.LAST
                                    else -> HistoryEntryGroupPosition.MIDDLE
                                },
                            onPlay = { onPlay(entry) },
                            onDelete = { onDelete(entry) },
                        )
                    }
                }
            }
        }
    }
}

private fun HistoryDateBucket.labelRes(): StringResource =
    when (this) {
        HistoryDateBucket.TODAY -> Res.string.today
        HistoryDateBucket.YESTERDAY -> Res.string.yesterday
        HistoryDateBucket.THIS_WEEK -> Res.string.this_week
        HistoryDateBucket.LAST_WEEK -> Res.string.last_week
        HistoryDateBucket.MONTH -> Res.string.recognition_history_earlier
    }

@Composable
private fun RecognitionHistoryRow(
    entry: RecognitionHistory,
    groupPosition: HistoryEntryGroupPosition,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val time =
        remember(entry.recognizedAt) {
            val millis = entry.recognizedAt.toInstant(TimeZone.UTC).toEpochMilliseconds()
            DateTimeFormatter
                .ofLocalizedTime(FormatStyle.SHORT)
                .format(
                    Instant
                        .ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault()),
                )
        }
    val playable = remember(entry) { entry.toSongItem() != null }
    val shape =
        when (groupPosition) {
            HistoryEntryGroupPosition.SINGLE -> ComponentShapes.MenuGroupShape
            HistoryEntryGroupPosition.FIRST -> RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 5.dp, bottomEnd = 5.dp)
            HistoryEntryGroupPosition.MIDDLE -> RoundedCornerShape(5.dp)
            HistoryEntryGroupPosition.LAST -> RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 1.dp)
                .clip(shape)
                .clickable(enabled = playable, onClick = onPlay),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = entry.coverArtHqUrl ?: entry.coverArtUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(ComponentShapes.SmallThumbnailShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${entry.artist}${entry.album?.let { " ·\u00a0$it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = time,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete, shapes = IconButtonDefaults.shapes()) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.Delete,
                    contentDescription = stringResource(Res.string.delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecognitionHistoryEmptyState(isSearching: Boolean) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        RecognitionBlobIcon(
            icon = MetrolistIcons.Outlined.History,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            blobSize = 140.dp,
            iconSize = 52.dp,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(if (isSearching) Res.string.no_matches else Res.string.no_recognized_songs_yet),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        if (!isSearching) {
            Text(
                text = stringResource(Res.string.no_recognized_songs_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private const val WAVEFORM_BAR_COUNT = 21

private enum class HeroKind { Capture, Processing, NoMatch, Error, NoMic, None }

private fun RecognitionStatus.heroKind(hasMicrophone: Boolean): HeroKind =
    when (this) {
        RecognitionStatus.Ready -> if (hasMicrophone) HeroKind.Capture else HeroKind.NoMic
        RecognitionStatus.Listening -> HeroKind.Capture
        RecognitionStatus.Processing -> HeroKind.Processing
        is RecognitionStatus.NoMatch -> HeroKind.NoMatch
        is RecognitionStatus.Error -> HeroKind.Error
        is RecognitionStatus.Success -> HeroKind.None
    }

private val HeroTotalHeight = 336.dp

private val HeroBlobCenterOffset = 216.dp

/** Keep the blob outside content transitions so state changes preserve its animation. */
@Composable
private fun RecognitionHero(
    kind: HeroKind,
    isListening: Boolean,
    onCapture: () -> Unit,
) {
    if (kind == HeroKind.None) return
    val captureLabel = stringResource(if (isListening) Res.string.cancel else Res.string.recognize)
    val motionActive = recognitionMotionActive()
    val audioLevel by MusicRecognitionService.audioLevel.collectAsState()
    val smoothLevel by animateFloatAsState(
        targetValue = if (kind == HeroKind.Capture) audioLevel else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "audio_level",
    )
    var elapsedSeconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(isListening) {
        if (!isListening) return@LaunchedEffect
        elapsedSeconds = 0
        while (true) {
            delay(1_000)
            elapsedSeconds++
        }
    }

    val outerPhase by rememberWavePhase(motionActive, 20_000L)
    val innerPhase by rememberWavePhase(motionActive, 15_000L)
    val phaseA by rememberWavePhase(motionActive, 8_000L)
    val phaseB by rememberWavePhase(motionActive, 10_000L)
    val heroHeight by animateDpAsState(
        targetValue = if (kind == HeroKind.None) 0.dp else HeroTotalHeight,
        animationSpec = if (kind == HeroKind.None) snap() else tween(350),
        label = "hero_height",
    )
    val heroAlpha by animateFloatAsState(
        targetValue = if (kind == HeroKind.None) 0f else 1f,
        animationSpec = if (kind == HeroKind.None) snap() else tween(250),
        label = "hero_alpha",
    )
    val outerSize by animateDpAsState(
        targetValue =
            when (kind) {
                HeroKind.Capture, HeroKind.Processing -> 200.dp
                HeroKind.None -> 0.dp
                else -> 150.dp
            },
        animationSpec = if (kind == HeroKind.None) snap() else tween(350),
        label = "outer_size",
    )
    val innerSize by animateDpAsState(
        targetValue = if (kind == HeroKind.Capture || kind == HeroKind.Processing) 128.dp else 0.dp,
        animationSpec = if (kind == HeroKind.None) snap() else tween(350),
        label = "inner_size",
    )
    val outerColor by animateColorAsState(
        targetValue =
            if (kind == HeroKind.Error || kind == HeroKind.NoMic) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.primaryContainer
            },
        animationSpec = tween(350),
        label = "outer_color",
    )

    Box(
        modifier = Modifier.fillMaxWidth().height(heroHeight).clipToBounds(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier.requiredHeight(HeroTotalHeight).graphicsLayer { alpha = heroAlpha },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = isListening,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(100)),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(Res.string.listening),
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = formatElapsed(elapsedSeconds),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp).clearAndSetSemantics { },
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Box(
                modifier = Modifier.size(240.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(outerSize)
                            .graphicsLayer {
                                rotationZ = outerPhase * 360f
                                scaleX = 1f + smoothLevel * 0.06f
                                scaleY = 1f + smoothLevel * 0.06f
                            }.graphicsLayer {
                                clip = true
                                shape =
                                    MorphingBlobShape(
                                        amplitude2 = 0.035f + 0.02f * smoothLevel,
                                        amplitude3 = 0.03f + 0.02f * smoothLevel,
                                        phase2 = phaseA * (2 * PI).toFloat(),
                                        phase3 = -phaseB * (2 * PI).toFloat(),
                                    )
                            }.background(outerColor),
                )
                Box(
                    modifier =
                        Modifier
                            .size(innerSize)
                            .graphicsLayer {
                                rotationZ = -innerPhase * 360f
                                clip = true
                                shape =
                                    MorphingBlobShape(
                                        amplitude2 = 0.02f + 0.015f * smoothLevel,
                                        amplitude3 = 0.025f + 0.015f * smoothLevel,
                                        phase2 = -phaseB * (2 * PI).toFloat(),
                                        phase3 = phaseA * (2 * PI).toFloat(),
                                    )
                            }.background(MaterialTheme.colorScheme.primary)
                            .then(
                                if (kind == HeroKind.Capture) {
                                    Modifier
                                        .tvFocusHighlight(CircleShape)
                                        .semantics { contentDescription = captureLabel }
                                        .clickable(role = Role.Button, onClickLabel = captureLabel, onClick = onCapture)
                                } else {
                                    Modifier
                                },
                            ),
                )
                Crossfade(targetState = kind, animationSpec = tween(250), label = "hero_icon") { target ->
                    val icon =
                        when (target) {
                            HeroKind.Capture, HeroKind.NoMic -> MetrolistIcons.Rounded.Mic
                            HeroKind.Processing -> MetrolistIcons.Rounded.MusicNote
                            HeroKind.NoMatch -> MetrolistIcons.Filled.SearchOff
                            HeroKind.Error -> MetrolistIcons.Filled.Error
                            HeroKind.None -> null
                        }
                    val tint =
                        when (target) {
                            HeroKind.Capture, HeroKind.Processing -> MaterialTheme.colorScheme.onPrimary
                            HeroKind.Error, HeroKind.NoMic -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        }
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        if (icon != null) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                modifier = Modifier.size(if (target == HeroKind.Capture || target == HeroKind.Processing) 44.dp else 56.dp),
                                tint = tint,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecognitionCaptureFooter(
    isListening: Boolean,
    onCancel: () -> Unit,
) {
    val audioLevel by MusicRecognitionService.audioLevel.collectAsState()
    val latestLevel by rememberUpdatedState(audioLevel)
    val levels =
        remember {
            mutableStateListOf<Float>().apply { repeat(WAVEFORM_BAR_COUNT) { add(0f) } }
        }
    LaunchedEffect(isListening) {
        if (!isListening) return@LaunchedEffect
        for (i in levels.indices) levels[i] = 0f
        while (true) {
            delay(70)
            levels.removeAt(0)
            levels.add(latestLevel)
        }
    }
    val idlePhase = rememberWavePhase(isListening && recognitionMotionActive(), 2_400L)

    Box(
        modifier = Modifier.fillMaxWidth().height(152.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = !isListening,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(100)),
        ) {
            Text(
                text = stringResource(Res.string.tap_to_recognize),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        AnimatedVisibility(
            visible = isListening,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(100)),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                RecognitionWaveform(
                    levels = levels,
                    idlePhase = { idlePhase.value * (2 * PI).toFloat() },
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.recognition_listening_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(24.dp))
                FilledTonalButton(
                    onClick = onCancel,
                    modifier = Modifier.tvFocusHighlight(RoundedCornerShape(100.dp)),
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Outlined.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.cancel))
                }
            }
        }
    }
}

@Composable
private fun RecognitionProcessingFooter() {
    Box(
        modifier = Modifier.fillMaxWidth().height(152.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(
            text = stringResource(Res.string.processing),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RecognitionWaveform(
    levels: List<Float>,
    idlePhase: () -> Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val barWidth = 5.dp.toPx()
        val gap = 4.dp.toPx()
        val count = levels.size
        val totalWidth = count * barWidth + (count - 1) * gap
        val startX = (size.width - totalWidth) / 2f
        val minHeight = 8.dp.toPx()
        levels.forEachIndexed { index, level ->
            val position = index / (count - 1).coerceAtLeast(1).toFloat()
            val idle =
                0.18f + 0.22f * (0.5f + 0.5f * sin(position * 2f * PI.toFloat() * 1.5f + idlePhase())) *
                    (0.6f + 0.4f * sin(position * PI.toFloat()))
            val amount = maxOf(idle, level.coerceIn(0f, 1f))
            val barHeight = minHeight + (size.height - minHeight) * amount
            val x = startX + index * (barWidth + gap) + barWidth / 2f
            drawLine(
                color = color,
                start = Offset(x, (size.height - barHeight) / 2f),
                end = Offset(x, (size.height + barHeight) / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

private val blobBasis =
    Array(241) { index ->
        val angle = 2.0 * PI * index / 240
        doubleArrayOf(cos(angle), sin(angle), cos(2 * angle), sin(2 * angle), cos(3 * angle), sin(3 * angle))
    }

@Composable
private fun recognitionMotionActive(): Boolean {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    return lifecycle == Lifecycle.State.RESUMED && LocalRootContentActive.current && !LocalContentObscured.current
}

private data class MorphingBlobShape(
    val amplitude2: Float,
    val amplitude3: Float,
    val phase2: Float,
    val phase3: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val baseRadius = minOf(size.width, size.height) / 2f
        val steps = 240
        val path = Path()
        val cos2 = cos(phase2.toDouble())
        val sin2 = sin(phase2.toDouble())
        val cos3 = cos(phase3.toDouble())
        val sin3 = sin(phase3.toDouble())
        for (i in 0..steps) {
            val basis = blobBasis[i]
            val deviation = amplitude2 * (basis[2] * cos2 - basis[3] * sin2) + amplitude3 * (basis[4] * cos3 - basis[5] * sin3)
            val radius = baseRadius * (1f - amplitude2 - amplitude3 + deviation.toFloat())
            val x = centerX + radius * basis[0].toFloat()
            val y = centerY + radius * basis[1].toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }
}

private fun formatElapsed(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

@Composable
private fun RecognitionSuccessState(
    result: RecognitionResult,
    historySaved: Boolean,
    song: SongItem?,
    isLiked: Boolean,
    onRetry: () -> Unit,
    onPlay: () -> Unit,
    onClose: () -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(Res.string.recognition_match_found),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Card(
            modifier = Modifier.size(200.dp),
            shape = RoundedCornerShape(28.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = result.coverArtHqUrl ?: result.coverArtUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = result.title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = result.artist,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp),
        )
        result.album?.let { album ->
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        if (!historySaved) {
            Text(
                text = stringResource(Res.string.recognition_history_save_failed),
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Spacer(Modifier.height(24.dp))

        RecognitionResultActions(
            song = song,
            isLiked = isLiked,
            onPlay = onPlay,
            onLike = onLike,
            onAddToPlaylist = onAddToPlaylist,
            onRetry = onRetry,
            onClose = onClose,
        )
    }
}

private val ResultButtonHeight = 56.dp
private val ResultIconButtonWidth = 64.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun connectedShapes(
    index: Int,
    count: Int,
): ToggleButtonShapes =
    when {
        count == 1 -> ToggleButtonDefaults.shapesFor(ToggleButtonDefaults.size)
        index == 0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
        index == count - 1 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
    }

/** ToggleButton supplies connected shapes; unchecked actions expose Button semantics. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ConnectedResultButton(
    onAction: () -> Unit,
    shapes: ToggleButtonShapes,
    colors: ToggleButtonColors,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    checked: Boolean = false,
    isToggle: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    content: @Composable RowScope.() -> Unit,
) {
    ToggleButton(
        checked = checked,
        onCheckedChange = { onAction() },
        enabled = enabled,
        shapes = shapes,
        colors = colors,
        contentPadding = contentPadding,
        modifier =
            modifier
                .height(ResultButtonHeight)
                .then(
                    if (isToggle) {
                        Modifier.semantics { this.contentDescription = description }
                    } else {
                        Modifier.clearAndSetSemantics {
                            this.contentDescription = description
                            role = Role.Button
                            if (!enabled) disabled()
                            onClick {
                                onAction()
                                true
                            }
                        }
                    },
                ),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RecognitionResultActions(
    song: SongItem?,
    isLiked: Boolean,
    onPlay: () -> Unit,
    onLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme
    val primaryColors =
        ToggleButtonDefaults.colors(
            containerColor = colorScheme.primary,
            contentColor = colorScheme.onPrimary,
            checkedContainerColor = colorScheme.primary,
            checkedContentColor = colorScheme.onPrimary,
        )
    val tonalColors =
        ToggleButtonDefaults.colors(
            containerColor = colorScheme.secondaryContainer,
            contentColor = colorScheme.onSecondaryContainer,
            checkedContainerColor = colorScheme.primary,
            checkedContentColor = colorScheme.onPrimary,
        )
    val connectedSpacing = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    val playLabel = stringResource(Res.string.play_on_app)
    val relistenLabel = stringResource(Res.string.re_listen)
    val closeLabel = stringResource(Res.string.close)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val firstGroupCount = if (song != null) 3 else 1
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = connectedSpacing,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectedResultButton(
                onAction = onPlay,
                enabled = song != null,
                shapes = connectedShapes(0, firstGroupCount),
                colors = primaryColors,
                description = playLabel,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = playLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (song != null) {
                val likeLabel = stringResource(if (isLiked) Res.string.liked else Res.string.action_like)
                ConnectedResultButton(
                    onAction = onLike,
                    checked = isLiked,
                    isToggle = true,
                    shapes = connectedShapes(1, firstGroupCount),
                    colors = tonalColors,
                    description = likeLabel,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(ResultIconButtonWidth),
                ) {
                    Icon(
                        imageVector = if (isLiked) MetrolistIcons.Filled.Favorite else MetrolistIcons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                val playlistLabel = stringResource(Res.string.add_to_playlist)
                ConnectedResultButton(
                    onAction = onAddToPlaylist,
                    shapes = connectedShapes(2, firstGroupCount),
                    colors = tonalColors,
                    description = playlistLabel,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(ResultIconButtonWidth),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.AutoMirrored.Outlined.PlaylistAdd,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = connectedSpacing,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ConnectedResultButton(
                onAction = onRetry,
                shapes = connectedShapes(0, 2),
                colors = tonalColors,
                description = relistenLabel,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Rounded.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = relistenLabel,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ConnectedResultButton(
                onAction = onClose,
                shapes = connectedShapes(1, 2),
                colors = tonalColors,
                description = closeLabel,
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.width(ResultIconButtonWidth),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.Close,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

@Composable
private fun RecognitionBlobIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    blobSize: androidx.compose.ui.unit.Dp = 150.dp,
    iconSize: androidx.compose.ui.unit.Dp = 56.dp,
) {
    val twoPi = (2.0 * PI).toFloat()
    val motionActive = recognitionMotionActive()
    val rotation by rememberWavePhase(motionActive, 20_000L)
    val morphA by rememberWavePhase(motionActive, 9_000L)
    val morphB by rememberWavePhase(motionActive, 11_000L)
    Box(
        modifier = modifier.size(blobSize + 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .size(blobSize)
                    .graphicsLayer { rotationZ = rotation * 360f }
                    .graphicsLayer {
                        clip = true
                        shape =
                            MorphingBlobShape(
                                amplitude2 = 0.04f,
                                amplitude3 = 0.035f,
                                phase2 = morphA * twoPi,
                                phase3 = -morphB * twoPi,
                            )
                    }.background(containerColor),
        )
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
    }
}

@Composable
private fun RecognitionMessageContent(
    title: String,
    message: String,
    onRetry: (() -> Unit)?,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp),
        )

        if (onRetry != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier.tvFocusHighlight(RoundedCornerShape(100.dp)),
                shapes = ButtonDefaults.shapes(),
            ) {
                Icon(
                    imageVector = MetrolistIcons.Outlined.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.try_again))
            }
        }
    }
}

@Composable
private fun RecognitionNoMatchContent(
    message: String,
    onTryAgain: () -> Unit,
) {
    RecognitionMessageContent(
        title = stringResource(Res.string.no_match_found),
        message = message.ifBlank { stringResource(Res.string.recognition_no_match_message) },
        onRetry = onTryAgain,
    )
}
