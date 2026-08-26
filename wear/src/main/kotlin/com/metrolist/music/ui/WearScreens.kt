/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URLDecoder
import kotlin.concurrent.thread
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import coil3.compose.AsyncImage
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import com.google.android.horologist.annotations.ExperimentalHorologistApi
import com.metrolist.music.constants.AudioNormalizationKey
import com.metrolist.music.constants.AutoDownloadOnLikeKey
import com.metrolist.music.constants.CrossfadeDurationKey
import com.metrolist.music.constants.CrossfadeEnabledKey
import com.metrolist.music.constants.EnableSongCacheKey
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.constants.HistoryDuration
import com.metrolist.music.constants.SleepTimerDefaultKey
import com.metrolist.music.constants.StopMusicOnTaskClearKey
import com.metrolist.music.constants.AppLanguageKey
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.utils.parseCookieString
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.LocalSyncUtils
import com.metrolist.music.WearApp
import com.metrolist.music.constants.*
import com.metrolist.music.constants.AuthSyncConstants.AUTH_REQUEST_PATH
import com.metrolist.music.core.R
import com.metrolist.music.db.entities.Song
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.LoginHelper
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.rememberPreference
import com.metrolist.music.viewmodels.OnlineSearchViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearMenuScreen(
    onNavigateToSearch: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val columnState = rememberResponsiveColumnState()
    val focusRequester = remember { FocusRequester() }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
    ) {
        item {
            ListHeader {
                Text(stringResource(R.string.options))
            }
        }
        item {
            Chip(
                onClick = onNavigateToSearch,
                label = { Text(stringResource(R.string.search)) },
                icon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToLogin,
                label = { Text(stringResource(R.string.login)) },
                icon = { Icon(painterResource(R.drawable.login), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToSettings,
                label = { Text(stringResource(R.string.settings)) },
                icon = { Icon(painterResource(R.drawable.settings), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearSearchScreen(
    onSearch: (String) -> Unit,
    onItemClick: () -> Unit = {}
) {
    // hiltViewModel is a Composable and cannot be wrapped in try-catch directly
    val actualViewModel: OnlineSearchViewModel = hiltViewModel()

    val playerConnection = LocalPlayerConnection.current
    val columnState = rememberResponsiveColumnState()
    val focusRequester = remember { FocusRequester() }
    
    val currentFilter by actualViewModel.filter.collectAsState()
    val searchLabel = stringResource(R.string.search)

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) {
                val resultsBundle = RemoteInput.getResultsFromIntent(result.data)
                val q = resultsBundle?.getCharSequence("search_query")?.toString()
                if (!q.isNullOrBlank()) {
                    onSearch(q)
                }
            }
        } catch (e: Exception) {
            Timber.tag("WearSearchScreen").e(e, "Error handling search result")
        }
    }

    LaunchedEffect(actualViewModel.query) {
        try {
            if (actualViewModel.query.isNotEmpty() && actualViewModel.filter.value == null) {
                actualViewModel.filter.value = YouTube.SearchFilter.FILTER_SONG
            }
        } catch (e: Exception) {
            Timber.tag("WearSearchScreen").e(e, "Error updating search filter")
        }
    }

    // Auto-launch keyboard if query is empty (initial search)
    LaunchedEffect(Unit) {
        if (actualViewModel.query.isEmpty()) {
            try {
                val remoteInput = RemoteInput.Builder("search_query")
                    .setLabel(searchLabel)
                    .build()
                val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                launcher.launch(intent)
            } catch (e: Exception) {
                Timber.tag("WearSearchScreen").e(e, "Failed to launch RemoteInput auto")
            }
        }
    }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
    ) {
        item {
            ListHeader {
                Text(
                    text = actualViewModel.query.ifEmpty { stringResource(R.string.search) },
                    textAlign = TextAlign.Center
                )
            }
        }
        
        item {
            Chip(
                onClick = {
                    try {
                        val remoteInput = RemoteInput.Builder("search_query")
                            .setLabel(searchLabel)
                            .build()
                        val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
                        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
                        launcher.launch(intent)
                    } catch (e: Exception) {
                        Timber.tag("WearSearchScreen").e(e, "Failed to launch RemoteInput")
                    }
                },
                label = { Text(stringResource(R.string.search)) },
                icon = { Icon(painterResource(R.drawable.search), contentDescription = null) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        if (YouTube.visitorData == null) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.listen_together_connecting),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else if (actualViewModel.query.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)
                ) {
                    val filters = listOf(
                        Triple(null, R.string.filter_all, R.drawable.grid_view),
                        Triple(YouTube.SearchFilter.FILTER_SONG, R.string.songs, R.drawable.music_note),
                        Triple(YouTube.SearchFilter.FILTER_ALBUM, R.string.albums, R.drawable.album),
                        Triple(YouTube.SearchFilter.FILTER_ARTIST, R.string.artists, R.drawable.artist),
                        Triple(YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST, R.string.playlists, R.drawable.library_music)
                    )
                    filters.forEach { (filter, labelRes, iconRes) ->
                        CompactChip(
                            onClick = {
                                try {
                                    actualViewModel.filter.value = filter
                                } catch (e: Exception) {
                                    Timber.tag("WearSearchScreen").e(e, "Failed to set filter")
                                }
                            },
                            label = {
                                Icon(
                                    painter = painterResource(iconRes),
                                    contentDescription = stringResource(labelRes),
                                    modifier = Modifier.size(ChipDefaults.SmallIconSize)
                                )
                            },
                            colors = if (currentFilter == filter)
                                ChipDefaults.primaryChipColors()
                            else
                                ChipDefaults.secondaryChipColors()
                        )
                    }
                }
            }

            val filterValue = currentFilter?.value ?: YouTube.SearchFilter.FILTER_SONG.value
            val itemsPage = actualViewModel.viewStateMap[filterValue]
            val results = itemsPage?.items.orEmpty()
            val isLoading = itemsPage == null

            if (isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (results.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_results_found),
                        modifier = Modifier.fillMaxWidth().padding(24.dp),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant
                    )
                }
            } else {
                items(results) { item ->
                    TitleCard(
                        onClick = { 
                            try {
                                val endpoint = when (item) {
                                    is SongItem -> WatchEndpoint(videoId = item.id)
                                    is AlbumItem -> WatchEndpoint(playlistId = item.id)
                                    is ArtistItem -> WatchEndpoint(playlistId = "VL${item.id}")
                                    else -> null
                                }
                                endpoint?.let { 
                                    playerConnection?.playQueue(YouTubeQueue(it)) 
                                    onItemClick()
                                }
                            } catch (e: Exception) {
                                Timber.tag("WearSearchScreen").e(e, "Failed to play item")
                            }
                        },
                        title = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        backgroundPainter = CardDefaults.cardBackgroundPainter(
                            startBackgroundColor = MaterialTheme.colors.surface,
                            endBackgroundColor = MaterialTheme.colors.surface
                        ),
                        contentColor = MaterialTheme.colors.onSurface,
                        titleColor = MaterialTheme.colors.onSurface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                model = item.thumbnail,
                                contentDescription = null,
                                placeholder = painterResource(R.drawable.music_note),
                                error = painterResource(R.drawable.music_note),
                                modifier = Modifier
                                    .size(24.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(Modifier.width(8.dp))
                            val subtitle = when (item) {
                                is SongItem -> item.artists.joinToString { it.name }
                                is AlbumItem -> item.artists?.joinToString { it.name }.orEmpty()
                                is ArtistItem -> stringResource(R.string.artists)
                                else -> ""
                            }
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.caption2,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colors.secondary
                                )
                            }
                        }
                    }
                }
                
                if (itemsPage.continuation != null) {
                    item {
                        Chip(
                            onClick = { 
                                try {
                                    actualViewModel.loadMore() 
                                } catch (e: Exception) {
                                    Timber.tag("WearSearchScreen").e(e, "Failed to load more")
                                }
                            },
                            label = { Text(stringResource(R.string.show_more)) },
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        )
                    }
                }
                
                item {
                    Spacer(Modifier.height(40.dp))
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLoginScreen() {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val columnState = rememberResponsiveColumnState()
    val focusRequester = remember { FocusRequester() }

    var isLoading by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var serverUrl by remember { mutableStateOf<String?>(null) }
    
    // Función mejorada para obtener la IP real del reloj
    fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            // Primero buscar en interfaces de Wi-Fi (wlan)
            val wifiIp = interfaces.filter { it.name.contains("wlan") || it.name.contains("eth") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull()
            
            if (wifiIp != null) return wifiIp

            // Fallback: cualquier IPv4 no local
            interfaces.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .filter { addr -> addr.hostAddress?.startsWith("10.0.") == false } // Evitar IPs de emulador/proxy si hay otras
                .mapNotNull { it.hostAddress }
                .firstOrNull() ?: 
            // Si no hay de otro tipo, aceptar la del emulador
            interfaces.flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress }
                .firstOrNull()
        } catch (e: Exception) {
            null
        }
    }

    val errorNoWifiIp = stringResource(R.string.error_no_wifi_ip)
    val loginInstructionStep2 = stringResource(R.string.login_instruction_step2).replace("2. ", "")
    val loginLabel = stringResource(R.string.login).uppercase()
    val wearSyncTitle = stringResource(R.string.wear_sync_title)
    val syncLibraryDesc = stringResource(R.string.sync_library_desc)
    val loginStartedRestart = stringResource(R.string.login_started_restart)
    val processingToken = stringResource(R.string.processing_token)
    val errorUnknown = stringResource(R.string.error_unknown)
    val loginFailed = stringResource(R.string.login_failed)

    // Lógica del servidor
    DisposableEffect(Unit) {
        var ip = getLocalIpAddress()
        
        var serverSocket: ServerSocket? = null
        val thread = thread {
            try {
                // Intentar usar el puerto 8080 primero, si no uno al azar
                serverSocket = try { ServerSocket(8080) } catch (e: Exception) { ServerSocket(0) }
                val port = serverSocket!!.localPort
                
                // Si la IP se detectó tarde, re-intentar
                if (ip == null) ip = getLocalIpAddress()
                
                if (ip != null) {
                    serverUrl = "http://$ip:$port"
                    Timber.tag("WearSyncServer").d("Server started at $serverUrl")
                } else {
                    statusMessage = errorNoWifiIp
                }
                
                while (!Thread.currentThread().isInterrupted) {
                    val client = try {
                        serverSocket!!.accept()
                    } catch (e: Exception) {
                        null
                    } ?: break
                    
                    val reader = client.getInputStream().bufferedReader()
                    val firstLine = reader.readLine() ?: continue
                    
                    Timber.tag("WearSyncServer").d("Request: $firstLine")
                    
                    if (firstLine.startsWith("GET")) {
                        val out = client.getOutputStream().bufferedWriter()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n\r\n")
                        out.write("""
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1">
                                <title>Metrolist Sync</title>
                                <style>
                                    body { font-family: sans-serif; padding: 20px; background: #121212; color: white; text-align: center; }
                                    .box { background: #1e1e1e; border-radius: 16px; padding: 15px; margin-bottom: 20px; border: 1px solid #333; }
                                    textarea { width: 100%; height: 250px; background: #222; color: #fff; border: 1px solid #444; border-radius: 8px; padding: 10px; font-size: 13px; box-sizing: border-box; font-family: monospace; }
                                    button { background: #BB86FC; color: #000; border: none; padding: 15px; border-radius: 30px; font-weight: bold; width: 100%; font-size: 16px; cursor: pointer; }
                                    .info { color: #aaa; font-size: 13px; margin-bottom: 20px; }
                                </style>
                            </head>
                            <body>
                                <h3>${wearSyncTitle}</h3>
                                <p class="info">${loginInstructionStep2}</p>
                                <form method="POST">
                                    <div class="box">
                                        <textarea name="sync_block" placeholder="**INNERTUBE COOKIE** =..."></textarea>
                                    </div>
                                    <button type="submit">${loginLabel}</button>
                                </form>
                                <p style="font-size: 10px; color: #555; margin-top: 20px;">${syncLibraryDesc}</p>
                            </body>
                            </html>
                        """.trimIndent())
                        out.flush()
                        client.close()
                    } else if (firstLine.startsWith("POST")) {
                        var contentLength = 0
                        var line = reader.readLine()
                        while (line != null && line.isNotEmpty()) {
                            if (line.startsWith("Content-Length:")) {
                                contentLength = line.substringAfter(": ").toInt()
                            }
                            line = reader.readLine()
                        }
                        
                        val body = CharArray(contentLength)
                        reader.read(body)
                        val rawData = String(body)
                        
                        // Extraer el valor de sync_block correctamente sin cortar en el primer '&' interno
                        val syncBlockParam = rawData.split("&").find { it.startsWith("sync_block=") }
                        val blockValueEncoded = syncBlockParam?.substringAfter("sync_block=") ?: ""
                        val block = URLDecoder.decode(blockValueEncoded, "UTF-8")
                        
                        Timber.tag("WearSyncServer").d("Received block: ${block.take(50)}...")

                        fun extractValue(key: String): String {
                            // Buscar la clave ignorando la cantidad de asteriscos (pueden ser 2 o 3)
                            // Buscamos algo como ***KEY*** = o **KEY** =
                            val regex = """\*+\s*${key}\s*\*+\s*=\s*([^*]+)""".toRegex(RegexOption.IGNORE_CASE)
                            val match = regex.find(block)
                            return match?.groupValues?.get(1)?.trim() ?: ""
                        }

                        val cookie = extractValue("INNERTUBE COOKIE")
                        val visitorData = extractValue("VISITOR DATA")
                        val dataSyncId = extractValue("DATASYNC ID")
                        
                        if (cookie.isNotBlank()) {
                            val out = client.getOutputStream().bufferedWriter()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${loginStartedRestart}")
                            out.flush()
                            client.close()

                            coroutineScope.launch {
                                try {
                                    statusMessage = processingToken
                                    isLoading = true
                                    
                                    val finalVisitorData = visitorData.ifBlank { 
                                        YouTube.visitorData().getOrNull().orEmpty() 
                                    }

                                    LoginHelper.finalizeLogin(
                                        context = context,
                                        cookie = cookie,
                                        visitorData = finalVisitorData,
                                        dataSyncId = dataSyncId,
                                        authUser = "0"
                                    )
                                } catch (e: Exception) {
                                    statusMessage = errorUnknown
                                    isLoading = false
                                    Timber.tag("WearSync").e(e)
                                }
                            }
                        } else {
                            val out = client.getOutputStream().bufferedWriter()
                            out.write("HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n${loginFailed}")
                            out.flush()
                            client.close()
                            statusMessage = loginFailed
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.tag("WearSyncServer").e(e)
            }
        }

        onDispose {
            thread.interrupt()
            try { serverSocket?.close() } catch (e: Exception) {}
        }
    }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize().focusRequester(focusRequester).focusable()
    ) {
        item { ListHeader { Text(stringResource(R.string.login)) } }

        if (serverUrl != null) {
            item {
                val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=$serverUrl"
                AsyncImage(
                    model = qrUrl,
                    contentDescription = "QR Code",
                    modifier = Modifier.size(110.dp).padding(4.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White)
                )
            }
            item {
                Text(
                    text = stringResource(R.string.wifi_warning).uppercase(),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            item {
                Text(
                    text = stringResource(R.string.login_instruction_step1) + "\n" + stringResource(R.string.login_instruction_step2),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
            item {
                Text(
                    text = serverUrl!!,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.primary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        } else {
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(statusMessage ?: stringResource(R.string.starting_server), style = MaterialTheme.typography.caption2)
                }
            }
        }
        
        if (isLoading) {
            item { CircularProgressIndicator(modifier = Modifier.padding(8.dp)) }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLibraryScreen(
    onNavigateToSongs: () -> Unit,
    onNavigateToAlbums: () -> Unit,
    onNavigateToArtists: () -> Unit,
    onNavigateToPlaylists: () -> Unit,
    onNavigateToLiked: () -> Unit,
    onNavigateToDownloads: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToLogin: () -> Unit
) {
    val context = LocalContext.current
    val columnState = rememberResponsiveColumnState()
    val syncUtils = LocalSyncUtils.current
    
    val cookie by remember {
        context.dataStore.data.map { it[InnerTubeCookieKey] }
    }.collectAsStateWithLifecycle(initialValue = null)

    val syncState by syncUtils.syncState.collectAsStateWithLifecycle()
    val syncCompletedStr = stringResource(R.string.sync_completed)
    val syncErrorStr = stringResource(R.string.sync_error)

    LaunchedEffect(syncState.overallStatus) {
        when (syncState.overallStatus) {
            com.metrolist.music.utils.SyncStatus.Completed -> {
                Toast.makeText(context, syncCompletedStr, Toast.LENGTH_SHORT).show()
            }
            is com.metrolist.music.utils.SyncStatus.Error -> {
                Toast.makeText(context, syncErrorStr, Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ListHeader {
                Text(stringResource(R.string.filter_library))
            }
        }
        item {
            val startingSyncStr = stringResource(R.string.starting_sync)
            Chip(
                onClick = { 
                    if (cookie == null) {
                        onNavigateToLogin()
                    } else {
                        Toast.makeText(context, startingSyncStr, Toast.LENGTH_SHORT).show()
                        syncUtils.performFullSync()
                    }
                },
                label = { Text(stringResource(R.string.sync_library)) },
                secondaryLabel = { Text(stringResource(R.string.sync_library_desc)) },
                icon = { Icon(painterResource(R.drawable.sync), contentDescription = null) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToLiked,
                label = { Text(stringResource(R.string.liked)) },
                icon = { Icon(painterResource(R.drawable.ic_heart), contentDescription = null, tint = MaterialTheme.colors.error) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToDownloads,
                label = { Text(stringResource(R.string.offline)) },
                icon = { Icon(painterResource(R.drawable.offline), contentDescription = null, tint = MaterialTheme.colors.primary) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToHistory,
                label = { Text(stringResource(R.string.history)) },
                icon = { Icon(painterResource(R.drawable.history), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToSongs,
                label = { Text(stringResource(R.string.songs)) },
                icon = { Icon(painterResource(R.drawable.music_note), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToAlbums,
                label = { Text(stringResource(R.string.albums)) },
                icon = { Icon(painterResource(R.drawable.album), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToArtists,
                label = { Text(stringResource(R.string.artists)) },
                icon = { Icon(painterResource(R.drawable.artist), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToPlaylists,
                label = { Text(stringResource(R.string.playlists)) },
                icon = { Icon(painterResource(R.drawable.playlist_play), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLibrarySongsScreen(
    filterLiked: Boolean = false,
    filterDownloaded: Boolean = false,
    filterHistory: Boolean = false,
    onItemClick: () -> Unit = {}
) {
    val database = LocalDatabase.current
    val playerConnection = LocalPlayerConnection.current
    val columnState = rememberResponsiveColumnState()
    
    val songs by remember(filterLiked, filterDownloaded, filterHistory) {
        when {
            filterLiked -> database.likedSongsByCreateDateAsc().map { it.reversed() }
            filterDownloaded -> database.allSongs().map { it.filter { s -> s.song.isDownloaded }.sortedByDescending { s -> s.song.dateDownload } }
            // History and all songs will just use all songs for now
            else -> database.songsByCreateDateAsc().map { it.reversed() }
        }
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ListHeader {
                Text(
                    text = when {
                        filterLiked -> stringResource(R.string.liked)
                        filterDownloaded -> stringResource(R.string.offline)
                        filterHistory -> stringResource(R.string.history)
                        else -> stringResource(R.string.songs)
                    }
                )
            }
        }

        if (songs.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_results_found),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.caption2
                )
            }
        } else {
            items(songs) { song ->
                Chip(
                    onClick = {
                        val endpoint = WatchEndpoint(videoId = song.id)
                        playerConnection?.playQueue(YouTubeQueue(endpoint))
                        onItemClick()
                    },
                    label = { Text(song.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { 
                        Text(
                            text = song.artists.joinToString { it.name },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    icon = {
                        AsyncImage(
                            model = song.song.thumbnailUrl,
                            contentDescription = null,
                            placeholder = painterResource(R.drawable.music_note),
                            error = painterResource(R.drawable.music_note),
                            modifier = Modifier
                                .size(ChipDefaults.IconSize)
                                .clip(CircleShape)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLibraryAlbumsScreen(onAlbumClick: (String) -> Unit) {
    val database = LocalDatabase.current
    val columnState = rememberResponsiveColumnState()
    val albums by remember { 
        database.albumsByCreateDateAsc().map { it.reversed() } 
    }.collectAsStateWithLifecycle(emptyList())

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item { ListHeader { Text(stringResource(R.string.albums)) } }
        items(albums) { album ->
            Chip(
                onClick = { onAlbumClick(album.id) },
                label = { Text(album.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(album.artists.joinToString { it.name }, maxLines = 1) },
                icon = {
                    AsyncImage(
                        model = album.album.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.size(ChipDefaults.IconSize).clip(CircleShape)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLibraryArtistsScreen(onArtistClick: (String) -> Unit) {
    val database = LocalDatabase.current
    val columnState = rememberResponsiveColumnState()
    val artists by remember { 
        database.artistsByPlayTimeAsc().map { it.reversed() } 
    }.collectAsStateWithLifecycle(emptyList())

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item { ListHeader { Text(stringResource(R.string.artists)) } }
        items(artists) { artist ->
            Chip(
                onClick = { onArtistClick(artist.id) },
                label = { Text(artist.artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = {
                    AsyncImage(
                        model = artist.artist.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.size(ChipDefaults.IconSize).clip(CircleShape)
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLibraryPlaylistsScreen(onPlaylistClick: (String) -> Unit) {
    val database = LocalDatabase.current
    val columnState = rememberResponsiveColumnState()
    
    val playlists by produceState(initialValue = emptyList<com.metrolist.music.db.entities.PlaylistEntity>()) {
        value = database.playlistEntitiesByNameAsc()
    }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item { ListHeader { Text(stringResource(R.string.playlists)) } }
        items(playlists) { playlist ->
            Chip(
                onClick = { onPlaylistClick(playlist.id) },
                label = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                icon = { Icon(painterResource(R.drawable.playlist_play), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearVolumeScreen() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService<AudioManager>()!! }
    val columnState = rememberResponsiveColumnState()
    
    var currentVolume by remember { 
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)) 
    }
    
    val outputDeviceName = remember {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bluetoothDevice = devices.find { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
        }
        bluetoothDevice?.productName?.toString() ?: context.getString(R.string.watch)
    }

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            ListHeader {
                Text(stringResource(R.string.volume), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                        )
                        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)
                ) {
                    Icon(painterResource(R.drawable.volume_down), contentDescription = stringResource(R.string.previous))
                }

                Text(
                    text = "$currentVolume",
                    style = MaterialTheme.typography.title2,
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.Center
                )

                Button(
                    onClick = {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            AudioManager.FLAG_SHOW_UI
                        )
                        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(ButtonDefaults.DefaultButtonSize)
                ) {
                    Icon(painterResource(R.drawable.volume_up), contentDescription = stringResource(R.string.next))
                }
            }
        }
        item {
            val isMuted = currentVolume == 0
            Chip(
                onClick = {
                    if (isMuted) {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_UNMUTE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    } else {
                        audioManager.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_MUTE,
                            AudioManager.FLAG_SHOW_UI
                        )
                    }
                    currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                },
                label = { Text(if (isMuted) stringResource(R.string.unmute) else stringResource(R.string.mute)) },
                icon = { 
                    Icon(
                        painterResource(if (isMuted) R.drawable.volume_up else R.drawable.volume_off), 
                        contentDescription = null 
                    ) 
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }
        
        item {
            Chip(
                onClick = {
                    try {
                        if (Build.VERSION.SDK_INT >= 31) {
                            val intent = Intent("android.media.action.LAUNCH_AUDIO_OUTPUT_SWITCHER")
                            intent.putExtra("android.media.extra.PACKAGE_NAME", context.packageName)
                            context.startActivity(intent)
                        } else {
                            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                            context.startActivity(intent)
                        }
                    } catch (_: Exception) {
                        // Fallback to Bluetooth settings
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                            context.startActivity(intent)
                        } catch (e2: Exception) {
                            Timber.tag("WearVolumeScreen").e(e2, "Failed to launch output switcher")
                        }
                    }
                },
                label = { Text(stringResource(R.string.audio_output)) },
                secondaryLabel = { Text(outputDeviceName) },
                icon = { Icon(painterResource(R.drawable.bluetooth), contentDescription = null, tint = MaterialTheme.colors.primary) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(top = 4.dp)
            )
        }
        
        item { Spacer(Modifier.height(40.dp)) }
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearSettingsScreen(
    onNavigateToLogin: () -> Unit = {},
    onNavigateToLanguage: () -> Unit = {},
    onNavigateToContentLanguage: () -> Unit = {},
    onNavigateToContentCountry: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val columnState = rememberResponsiveColumnState()
    val focusRequester = remember { FocusRequester() }

    var hideExplicit by rememberPreference(key = HideExplicitKey, defaultValue = false)
    var hideVideoSongs by rememberPreference(key = HideVideoSongsKey, defaultValue = false)
    var crossfadeEnabled by rememberPreference(key = CrossfadeEnabledKey, defaultValue = false)
    var crossfadeDuration by rememberPreference(key = CrossfadeDurationKey, defaultValue = 5f)
    var audioNormalization by rememberPreference(key = AudioNormalizationKey, defaultValue = true)
    var skipSilence by rememberPreference(key = SkipSilenceKey, defaultValue = false)
    var sleepTimerDuration by rememberPreference(key = SleepTimerDefaultKey, defaultValue = 30f)
    var enableSongCache by rememberPreference(key = EnableSongCacheKey, defaultValue = true)
    var autoDownloadOnLike by rememberPreference(key = AutoDownloadOnLikeKey, defaultValue = false)
    var historyDuration by rememberPreference(key = HistoryDuration, defaultValue = 30f)
    var stopMusicOnTaskClear by rememberPreference(key = StopMusicOnTaskClearKey, defaultValue = true)
    val appLanguage by rememberPreference(key = AppLanguageKey, defaultValue = SYSTEM_DEFAULT)
    val contentLanguage by rememberPreference(key = ContentLanguageKey, defaultValue = SYSTEM_DEFAULT)
    val contentCountry by rememberPreference(key = ContentCountryKey, defaultValue = SYSTEM_DEFAULT)

    val accountName by remember {
        context.dataStore.data.map { it[AccountNameKey] ?: it[AccountEmailKey] }
    }.collectAsStateWithLifecycle(initialValue = null)

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
    ) {
        item {
            ListHeader {
                Text(stringResource(R.string.settings))
            }
        }

        // Account Section
        item {
            ListHeader {
                Text(stringResource(R.string.account), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            Chip(
                onClick = {
                    if (accountName != null) {
                        coroutineScope.launch {
                            WearApp.forgetAccount(context)
                        }
                    } else {
                        onNavigateToLogin()
                    }
                },
                label = { Text(accountName ?: stringResource(R.string.not_logged_in)) },
                secondaryLabel = { Text(if (accountName != null) stringResource(R.string.action_logout) else stringResource(R.string.action_login)) },
                icon = { Icon(painterResource(R.drawable.account), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Language Section
        item {
            ListHeader {
                Text(stringResource(R.string.app_language), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            Chip(
                onClick = onNavigateToLanguage,
                label = { Text(stringResource(R.string.app_language)) },
                secondaryLabel = {
                    Text(
                        if (appLanguage == SYSTEM_DEFAULT) stringResource(R.string.system_default)
                        else LanguageCodeToName[appLanguage] ?: appLanguage
                    )
                },
                icon = { Icon(painterResource(R.drawable.language), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToContentLanguage,
                label = { Text(stringResource(R.string.content_language)) },
                secondaryLabel = {
                    Text(
                        if (contentLanguage == SYSTEM_DEFAULT) stringResource(R.string.system_default)
                        else LanguageCodeToName[contentLanguage] ?: contentLanguage
                    )
                },
                icon = { Icon(painterResource(R.drawable.language), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Chip(
                onClick = onNavigateToContentCountry,
                label = { Text(stringResource(R.string.content_country)) },
                secondaryLabel = {
                    Text(
                        if (contentCountry == SYSTEM_DEFAULT) stringResource(R.string.system_default)
                        else CountryCodeToName[contentCountry] ?: contentCountry
                    )
                },
                icon = { Icon(painterResource(R.drawable.language), contentDescription = null) },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Playback Section
        item {
            ListHeader {
                Text(stringResource(R.string.player), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            ToggleChip(
                checked = crossfadeEnabled,
                onCheckedChange = { crossfadeEnabled = it },
                label = { Text(stringResource(R.string.crossfade)) },
                secondaryLabel = { Text(if (crossfadeEnabled) stringResource(R.string.active_format, crossfadeDuration.toInt()) else stringResource(R.string.crossfade_desc)) },
                toggleControl = {
                    Checkbox(checked = crossfadeEnabled)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        if (crossfadeEnabled) {
            item {
                Chip(
                    onClick = {
                        val next = crossfadeDuration + 1f
                        crossfadeDuration = if (next > 15f) 1f else next
                    },
                    label = { Text(stringResource(R.string.adjust_duration)) },
                    secondaryLabel = { Text(pluralStringResource(R.plurals.seconds, crossfadeDuration.toInt(), crossfadeDuration.toInt())) },
                    icon = { Icon(painterResource(R.drawable.more_time), contentDescription = null) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
                )
            }
        }

        item {
            ToggleChip(
                checked = audioNormalization,
                onCheckedChange = { audioNormalization = it },
                label = { Text(stringResource(R.string.audio_normalization)) },
                toggleControl = {
                    Checkbox(checked = audioNormalization)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = stopMusicOnTaskClear,
                onCheckedChange = { stopMusicOnTaskClear = it },
                label = { Text(stringResource(R.string.stop_music_on_task_clear)) },
                toggleControl = {
                    Checkbox(checked = stopMusicOnTaskClear)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = skipSilence,
                onCheckedChange = { skipSilence = it },
                label = { Text(stringResource(R.string.skip_silence)) },
                toggleControl = {
                    Checkbox(checked = skipSilence)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            ListHeader {
                Text(stringResource(R.string.sleep_timer), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            Chip(
                onClick = {
                    val next = sleepTimerDuration + 5f
                    sleepTimerDuration = if (next > 120f) 5f else next
                },
                label = { Text(stringResource(R.string.sleep_timer)) },
                secondaryLabel = { Text(pluralStringResource(R.plurals.minute, sleepTimerDuration.toInt(), sleepTimerDuration.toInt())) },
                icon = { Icon(painterResource(R.drawable.timer), contentDescription = null) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        item {
            ListHeader {
                Text(stringResource(R.string.history), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            Chip(
                onClick = {
                    val next = historyDuration + 10f
                    historyDuration = if (next > 120f) 10f else next
                },
                label = { Text(stringResource(R.string.history_duration)) },
                secondaryLabel = { Text(pluralStringResource(R.plurals.seconds, historyDuration.toInt(), historyDuration.toInt())) },
                icon = { Icon(painterResource(R.drawable.history), contentDescription = null) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
            )
        }

        // Content Section
        item {
            ListHeader {
                Text(stringResource(R.string.content), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            ToggleChip(
                checked = hideExplicit,
                onCheckedChange = { hideExplicit = it },
                label = { Text(stringResource(R.string.hide_explicit)) },
                toggleControl = {
                    Checkbox(checked = hideExplicit)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = hideVideoSongs,
                onCheckedChange = { hideVideoSongs = it },
                label = { Text(stringResource(R.string.hide_video_songs)) },
                toggleControl = {
                    Checkbox(checked = hideVideoSongs)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Storage Section
        item {
            ListHeader {
                Text(stringResource(R.string.storage), style = MaterialTheme.typography.caption2)
            }
        }
        item {
            ToggleChip(
                checked = enableSongCache,
                onCheckedChange = { enableSongCache = it },
                label = { Text(stringResource(R.string.enable_song_cache)) },
                secondaryLabel = { Text(stringResource(R.string.enable_song_cache_desc)) },
                toggleControl = {
                    Checkbox(checked = enableSongCache)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = autoDownloadOnLike,
                onCheckedChange = { autoDownloadOnLike = it },
                label = { Text(stringResource(R.string.auto_download_on_like)) },
                secondaryLabel = { Text(stringResource(R.string.auto_download_on_like_desc)) },
                toggleControl = {
                    Checkbox(checked = autoDownloadOnLike)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        
        item { Spacer(Modifier.height(40.dp)) }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

@OptIn(ExperimentalHorologistApi::class)
@Composable
fun WearLanguageScreen(
    title: String,
    preferenceKey: androidx.datastore.preferences.core.Preferences.Key<String>,
    options: Map<String, String>,
    onSelected: () -> Unit = {}
) {
    val columnState = rememberResponsiveColumnState()
    val focusRequester = remember { FocusRequester() }

    var selectedValue by rememberPreference(key = preferenceKey, defaultValue = SYSTEM_DEFAULT)

    ScalingLazyColumn(
        columnState = columnState,
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
    ) {
        item {
            ListHeader {
                Text(title)
            }
        }

        item {
            ToggleChip(
                checked = selectedValue == SYSTEM_DEFAULT,
                onCheckedChange = { 
                    if (it) {
                        selectedValue = SYSTEM_DEFAULT
                        onSelected()
                    }
                },
                label = { Text(stringResource(R.string.system_default)) },
                toggleControl = {
                    RadioButton(selected = selectedValue == SYSTEM_DEFAULT)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        val sortedOptions = options.entries.sortedBy { it.value }
        items(sortedOptions) { (code, name) ->
            ToggleChip(
                checked = selectedValue == code,
                onCheckedChange = { 
                    if (it) {
                        selectedValue = code
                        onSelected()
                    }
                },
                label = { Text(name) },
                toggleControl = {
                    RadioButton(selected = selectedValue == code)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}
