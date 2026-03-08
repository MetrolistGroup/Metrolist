package com.metrolist.shared.state

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.withDatabaseTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

internal fun MusicDatabase.isArtistSubscribedFlow(artistId: String) = dao().isArtistBookmarked(artistId).distinctUntilChanged()

@Composable
fun rememberArtistSubscription(
    database: MusicDatabase,
    artistId: String?,
): Boolean {
    if (artistId == null) return false
    return key(database, artistId) {
        val flow = remember(database, artistId) { database.isArtistSubscribedFlow(artistId) }
        val subscribed by flow.collectAsState(initial = false)
        subscribed
    }
}

internal suspend fun MusicDatabase.updateArtistSubscribed(
    artistId: String,
    artistName: String,
    subscribed: Boolean? = null,
): Boolean =
    withDatabaseTransaction {
        val entity = dao().getArtistById(artistId) ?: ArtistEntity(id = artistId, name = artistName)
        val nextSubscribed = subscribed ?: (entity.bookmarkedAt == null)
        dao().upsert(
            entity.copy(
                bookmarkedAt = if (nextSubscribed) entity.bookmarkedAt ?: Clock.System.now().toLocalDateTime(TimeZone.UTC) else null,
            ),
        )
        nextSubscribed
    }

private val artistSubscriptionMutex = Mutex()

fun toggleArtistSubscription(
    scope: CoroutineScope,
    database: MusicDatabase,
    repository: YouTubeRepository,
    artistId: String,
    artistName: String,
    onSubscriptionChanged: (Boolean) -> Unit,
    onError: () -> Unit,
) = scope.launch {
    artistSubscriptionMutex.withLock {
        val subscribed =
            try {
                val nextSubscribed = database.dao().getArtistById(artistId)?.bookmarkedAt == null
                if (repository.hasAuthenticatedSession && !repository.subscribeChannel(artistId, nextSubscribed)) {
                    onError()
                    return@withLock
                }
                database.updateArtistSubscribed(artistId, artistName, nextSubscribed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                onError()
                return@withLock
            }
        onSubscriptionChanged(subscribed)
    }
}
