package com.metrolist.shared.state

import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.syncRemoteLibraryItems
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.youtube.logging.YtLogger
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.mp.KoinPlatform
import kotlin.time.Clock

object SongLibraryState {
    private const val TAG = "SongLibraryState"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Songs from lists keep their YouTube tokens until the list reloads, so this session's choice wins over them.
    private val choices = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    fun canToggle(song: SongItem): Boolean = !song.isLocal && !song.isUploaded && song.podcastId == null

    fun isInLibrary(song: SongItem): Boolean = choices.value[song.id] ?: SongMetadataState.isInLibrary(song)

    fun toggle(song: SongItem) {
        val saved = !isInLibrary(song)
        choices.update { it + (song.id to saved) }
        scope.launch {
            try {
                val database = KoinPlatform.getKoin().get<MusicDatabase>()
                database.setSongInLibrary(song, saved, Clock.System.now().toLocalDateTime(TimeZone.UTC))
                val token = if (saved) song.libraryAddToken else song.libraryRemoveToken
                val innerTube = GlobalInnerTube.instance
                if (token != null && innerTube.hasSapCookieAuth()) {
                    val response = innerTube.feedback(YouTubeClient.WEB_REMIX, listOf(token))
                    check(response.status.isSuccess()) { "Library feedback failed with HTTP ${response.status.value}" }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                YtLogger.e(TAG, "Could not update the library", e)
            }
        }
    }
}

internal suspend fun MusicDatabase.setSongInLibrary(
    song: SongItem,
    saved: Boolean,
    now: LocalDateTime,
) {
    if (saved) syncRemoteLibraryItems(listOf(song), now) else dao().inLibrary(song.id, null)
}
