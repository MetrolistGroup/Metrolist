package com.metrolist.music.viewmodels

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.R
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.utils.SpotifyApiException
import com.metrolist.music.utils.SpotifyImportClient
import com.metrolist.music.utils.SpotifyTrackMatcher
import com.metrolist.music.utils.SpotifyLikedTrack
import com.metrolist.music.utils.SpotifyLibraryExportParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject

data class SpotifyImportState(
    val running: Boolean = false,
    val processed: Int = 0,
    val total: Int = 0,
    val added: Int = 0,
    val existing: Int = 0,
    val skipped: Int = 0,
    val current: String = "",
    val message: String = "",
    val unmatched: List<String> = emptyList(),
)

@HiltViewModel
class SpotifyImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {
    private val preferences = context.getSharedPreferences("spotify-import", Context.MODE_PRIVATE)
    val clientId get() = preferences.getString("client-id", "").orEmpty()
    private val client = SpotifyImportClient(context)
    private var job: Job? = null
    private val mutableState = MutableStateFlow(SpotifyImportState())
    val state = mutableState.asStateFlow()

    fun disconnect() {
        if (job?.isActive == true) return
        client.disconnect()
        preferences.edit().remove("client-id").apply()
        mutableState.value = SpotifyImportState()
    }

    fun cancel() { job?.cancel() }

    private suspend fun importTracks(tracks: List<SpotifyLikedTrack>, unavailable: Int, total: Int) {
        mutableState.value = mutableState.value.let {
            it.copy(total = total, processed = it.processed + unavailable, skipped = it.skipped + unavailable)
        }
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            mutableState.value = mutableState.value.copy(current = "${track.title} — ${track.artists.joinToString()}")
            val candidates = YouTube.search("${track.title} ${track.artists.joinToString(" ")}", YouTube.SearchFilter.FILTER_SONG)
                .getOrThrow().items.filterIsInstance<SongItem>()
            currentCoroutineContext().ensureActive()
            val match = SpotifyTrackMatcher.match(track, candidates)
            var alreadyLiked = false
            if (match != null) {
                database.withTransaction {
                    val existing = song(match.id).first()?.song
                    alreadyLiked = existing?.liked == true
                    if (existing == null) insert(match.toMediaMetadata()) { it.toggleLike(syncToYouTube = false) }
                    else if (!alreadyLiked) update(existing.toggleLike(syncToYouTube = false))
                }
            }
            mutableState.value = mutableState.value.let {
                it.copy(processed = it.processed + 1,
                    added = it.added + if (match != null && !alreadyLiked) 1 else 0,
                    existing = it.existing + if (alreadyLiked) 1 else 0,
                    skipped = it.skipped + if (match == null) 1 else 0,
                    unmatched = if (match == null) it.unmatched + "${track.title} — ${track.artists.joinToString()}" else it.unmatched)
            }
            delay(250)
        }
    }

    fun startFile(uri: Uri) {
        if (job?.isActive == true) return
        mutableState.value = SpotifyImportState(running = true, message = context.getString(R.string.spotify_importing))
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 20 * 1024 * 1024) { "File too large" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } ?: throw IllegalArgumentException("Unreadable file")
                val library = SpotifyLibraryExportParser.parse(text)
                require(library.tracks.isNotEmpty()) { "No saved songs in export" }
                importTracks(library.tracks, library.skipped, library.tracks.size + library.skipped)
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_complete))
            } catch (_: CancellationException) {
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_cancelled))
            } catch (_: IllegalArgumentException) {
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_file_error))
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_network_error))
            } finally {
                mutableState.value = mutableState.value.copy(running = false, current = "")
            }
        }
    }

    fun start(id: String) {
        if (job?.isActive == true) return
        val cleanedId = id.trim()
        if (!Regex("[a-fA-F0-9]{32}").matches(cleanedId)) {
            mutableState.value = SpotifyImportState(message = context.getString(R.string.spotify_invalid_client))
            return
        }
        preferences.edit().putString("client-id", cleanedId).apply()
        mutableState.value = SpotifyImportState(running = true, message = context.getString(R.string.spotify_connecting))
        job = viewModelScope.launch(Dispatchers.IO) {
            try {
                client.connect(cleanedId) { url ->
                    withContext(Dispatchers.Main) {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                }
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_importing))
                client.likedTracks(cleanedId) { tracks, unavailable, total ->
                    importTracks(tracks, unavailable, total)
                }
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_complete))
            } catch (_: CancellationException) {
                mutableState.value = mutableState.value.copy(message = context.getString(R.string.spotify_cancelled))
            } catch (error: Exception) {
                val message = when ((error as? SpotifyApiException)?.status) {
                    400 -> R.string.spotify_setup_error
                    401 -> R.string.spotify_auth_error
                    403 -> R.string.spotify_access_error
                    408 -> R.string.spotify_timeout
                    429 -> R.string.spotify_rate_error
                    else -> R.string.spotify_network_error
                }
                mutableState.value = mutableState.value.copy(message = context.getString(message))
            } finally {
                mutableState.value = mutableState.value.copy(running = false, current = "")
            }
        }
    }
}
