package com.metrolist.shared.playlist

import com.metrolist.shared.api.PlaylistSongAlreadyPresentException
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.db.entities.PlaylistSongMap
import com.metrolist.shared.db.upsertHistorySong
import com.metrolist.shared.db.withDatabaseTransaction
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.playlist.LibraryDeletionTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

class PlaylistPartiallyUpdatedException(
    val updatedName: String,
    cause: Throwable,
) : Exception(cause)

private val playlistBookmarkMutex = Mutex()

suspend fun setStoredPlaylistBookmarked(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
    playlist: PlaylistItem,
    bookmarked: Boolean,
    songCount: Int?,
): Result<Unit> =
    playlistMutation {
        playlistBookmarkMutex.withLock {
            val accountKey = LibraryBookmarkMutations.capture().accountKey
            val dao = database.dao()
            val existing = dao.findPlaylistEntity(id)
            val remoteId = if (playlist.isLocal) null else remotePlaylistId(existing, id)
            if (remoteId != null && repository.canManagePlaylists()) {
                repository.setPlaylistSaved(remoteId, bookmarked).getOrThrow()
            }
            LibraryBookmarkMutations.withLock {
                database.withDatabaseTransaction {
                    val current = dao.findPlaylistEntity(id)
                    dao.upsert(
                        (current ?: PlaylistEntity(name = playlist.title, browseId = remoteId, isLocal = remoteId == null)).copy(
                            name = playlist.title,
                            thumbnailUrl = playlist.thumbnail ?: current?.thumbnailUrl,
                            remoteSongCount = songCount ?: current?.remoteSongCount,
                            isAutoSync = current?.isAutoSync ?: (remoteId != null),
                            playEndpointParams = playlist.playEndpointParams ?: current?.playEndpointParams,
                            radioEndpointParams = playlist.radioEndpointParams ?: current?.radioEndpointParams,
                            isEditable = playlist.canEdit,
                            description = playlist.description ?: current?.description,
                            bookmarkedAt = if (bookmarked) Clock.System.now().toLocalDateTime(TimeZone.UTC) else null,
                        ),
                    )
                }
                LibraryBookmarkMutations.record(accountKey, "playlist:${(remoteId ?: id).removePrefix("VL")}", bookmarked)
            }
        }
    }

suspend fun removeSongsFromStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    playlistId: String,
    songs: List<SongItem>,
): Result<Unit> =
    playlistMutation {
        if (songs.isEmpty()) return@playlistMutation
        val entity =
            database.dao().findPlaylistEntity(playlistId)
                ?: database.dao().findPlaylistEntity(playlistId.removePrefix("VL"))
        val remoteId = remotePlaylistId(entity, playlistId)
        if (remoteId == null && entity == null) throw NoSuchElementException()
        if (entity != null) check(entity.isEditable)
        val localId = entity?.id ?: playlistId

        val playlistSongs =
            if (entity != null || playlistId.startsWith("LP")) {
                database.dao().playlistSongs(localId).first()
            } else {
                database.dao().findPlaylistEntity(playlistId.removePrefix("VL"))?.let {
                    database.dao().playlistSongs(it.id).first()
                } ?: emptyList()
            }
        val available = playlistSongs.toMutableList()

        val matchedPairs =
            songs.map { song ->
                val occurrence =
                    if (remoteId == null && song.playlistLocalMappingId != null) {
                        available.firstOrNull { it.map.id == song.playlistLocalMappingId && it.map.songId == song.id }
                    } else if (remoteId != null && song.playlistSetVideoId != null) {
                        available.firstOrNull { it.map.setVideoId == song.playlistSetVideoId }
                    } else {
                        available.firstOrNull { it.map.songId == song.id }
                    }
                occurrence?.also { available.remove(it) }
                song to occurrence
            }

        if (remoteId != null) {
            check(repository.canManagePlaylists()) { "YouTube Music sign-in is required" }
            val remoteRemovals =
                matchedPairs.map { (song, matchedOccurrence) ->
                    val setVideoId =
                        song.playlistSetVideoId
                            ?: matchedOccurrence?.map?.setVideoId
                    checkNotNull(setVideoId) { "Missing playlist item identifier" } to song.id
                }
            repository.removePlaylistSongs(remoteId, remoteRemovals).getOrThrow()
        }

        val matchedEntities = matchedPairs.mapNotNull { it.second }
        if (entity != null || matchedEntities.isNotEmpty()) {
            val targetLocalId = entity?.id ?: localId
            playlistAddMutex.withLock {
                database.withDatabaseTransaction {
                    val dao = database.dao()
                    val currentEntity = dao.findPlaylistEntity(targetLocalId) ?: return@withDatabaseTransaction
                    val occurrenceIds = matchedEntities.mapTo(mutableSetOf()) { it.map.id }
                    val allMappings = dao.playlistSongMaps(targetLocalId, 0)
                    val currentMappings = allMappings.filter { it.id in occurrenceIds }
                    dao.deletePlaylistMappings(targetLocalId, currentMappings.map { it.id })
                    var removedBefore = 0
                    var minimumPosition = Int.MIN_VALUE
                    allMappings.groupBy { it.position }.values.forEach { atPosition ->
                        atPosition.filterNot { it.id in occurrenceIds }.forEach { mapping ->
                            val position = maxOf(mapping.position - removedBefore, minimumPosition)
                            if (position != mapping.position) dao.updatePlaylistMappingPosition(mapping.id, position)
                        }
                        val removedAtPosition = atPosition.count { it.id in occurrenceIds }
                        if (removedAtPosition > 0) {
                            minimumPosition = maxOf(minimumPosition, atPosition.first().position - removedBefore)
                            removedBefore += removedAtPosition
                        }
                    }
                    dao.update(
                        currentEntity.copy(
                            remoteSongCount =
                                if (remoteId != null && currentMappings.size != songs.size) {
                                    null
                                } else {
                                    currentEntity.remoteSongCount?.let { (it - currentMappings.size).coerceAtLeast(0) }
                                },
                            lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                        ),
                    )
                }
            }
        }
        PlaylistChanges.notifyChanged(remoteId ?: localId)
    }

suspend fun moveSongInLocalPlaylist(
    database: MusicDatabase,
    playlistId: String,
    mappingId: Int,
    successorMappingId: Int?,
): Result<Unit> =
    playlistMutation {
        playlistAddMutex.withLock {
            database.withDatabaseTransaction {
                val dao = database.dao()
                val entity = checkNotNull(dao.findPlaylistEntity(playlistId))
                check(remotePlaylistId(entity, playlistId) == null && entity.isEditable)
                val mappings = dao.playlistSongMaps(entity.id, 0)
                val moving = checkNotNull(mappings.firstOrNull { it.id == mappingId })
                val successor = successorMappingId?.let { id -> checkNotNull(mappings.firstOrNull { it.id == id }) }
                require(successor?.id != moving.id)
                val destination =
                    when {
                        successor == null -> mappings.last().position
                        successor.position > moving.position -> successor.position - 1
                        else -> successor.position
                    }
                dao.move(entity.id, moving.position, destination)
                dao.update(entity.copy(lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC)))
            }
        }
    }

suspend fun moveSongInStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    playlistId: String,
    mappingId: Int,
    successorMappingId: Int?,
): Result<Unit> =
    playlistMutation {
        playlistAddMutex.withLock {
            val dao = database.dao()
            val entity = checkNotNull(dao.findPlaylistEntity(playlistId))
            check(entity.isEditable)
            val mappings = dao.playlistSongMaps(entity.id, 0)
            val moving = checkNotNull(mappings.firstOrNull { it.id == mappingId })
            val successor = successorMappingId?.let { id -> checkNotNull(mappings.firstOrNull { it.id == id }) }
            require(successor?.id != moving.id)
            val remoteId = remotePlaylistId(entity, playlistId)
            if (remoteId != null) {
                check(repository.canManagePlaylists())
                repository
                    .movePlaylistSong(
                        remoteId,
                        checkNotNull(moving.setVideoId),
                        successor?.let { checkNotNull(it.setVideoId) },
                    ).getOrThrow()
            }
            database.withDatabaseTransaction {
                val currentEntity = checkNotNull(dao.findPlaylistEntity(entity.id))
                val currentMappings = dao.playlistSongMaps(entity.id, 0)
                val currentMoving = checkNotNull(currentMappings.firstOrNull { it.id == moving.id })
                val currentSuccessor = successor?.let { next -> checkNotNull(currentMappings.firstOrNull { it.id == next.id }) }
                val destination =
                    when {
                        currentSuccessor == null -> currentMappings.last().position
                        currentSuccessor.position > currentMoving.position -> currentSuccessor.position - 1
                        else -> currentSuccessor.position
                    }
                dao.move(entity.id, currentMoving.position, destination)
                dao.update(currentEntity.copy(lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC)))
            }
        }
    }

suspend fun removeSongFromLocalPlaylist(
    database: MusicDatabase,
    mapping: PlaylistSongMap,
): Result<Unit> =
    playlistMutation {
        database.withDatabaseTransaction {
            val dao = database.dao()
            val entity = checkNotNull(dao.findPlaylistEntity(mapping.playlistId))
            check(entity.browseId == null && entity.isEditable)
            val current =
                dao.playlistSongMaps(mapping.songId).firstOrNull {
                    it.id == mapping.id && it.playlistId == entity.id
                } ?: return@withDatabaseTransaction
            dao.delete(current)
            dao.decrementPositionsAfter(entity.id, current.position)
            dao.update(
                entity.copy(
                    remoteSongCount = dao.playlistSongMaps(entity.id, 0).size,
                    lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                ),
            )
        }
    }

suspend fun moveSongInLocalPlaylist(
    database: MusicDatabase,
    playlistId: String,
    fromMappingId: Int,
    toMappingId: Int,
): Result<Unit> =
    playlistMutation {
        database.withDatabaseTransaction {
            val dao = database.dao()
            val playlist = checkNotNull(dao.findPlaylistEntity(playlistId))
            check(playlist.browseId == null && playlist.isEditable)
            val mappings = dao.playlistSongMaps(playlist.id, 0)
            val from = checkNotNull(mappings.firstOrNull { it.id == fromMappingId })
            val to = checkNotNull(mappings.firstOrNull { it.id == toMappingId })
            dao.move(playlist.id, from.position, to.position)
        }
    }

suspend fun renameStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
    name: String,
): Result<Unit> =
    playlistMutation {
        val entity = database.dao().findPlaylistEntity(id)
        val remoteId = remotePlaylistId(entity, id)
        if (remoteId == null && entity == null) throw NoSuchElementException()
        val normalizedName = name.trim()
        if (remoteId != null) repository.renamePlaylist(remoteId, normalizedName).getOrThrow()
        updateLocalPlaylist(database, entity, normalizedName, entity?.description)
        PlaylistChanges.notifyChanged(remoteId ?: entity?.id ?: id)
    }

suspend fun updateStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
    currentName: String,
    currentDescription: String?,
    name: String,
    description: String,
): Result<Unit> =
    playlistMutation {
        val entity = database.dao().findPlaylistEntity(id)
        val remoteId = remotePlaylistId(entity, id)
        val normalizedName = name.trim()
        val normalizedDescription = description.trim()
        if (remoteId == null && entity == null) throw NoSuchElementException()
        if (remoteId != null) {
            if (normalizedName != currentName) {
                repository.renamePlaylist(remoteId, normalizedName).getOrThrow()
                updateLocalPlaylist(database, entity, normalizedName, entity?.description)
            }
            if (normalizedDescription != currentDescription.orEmpty()) {
                val result = repository.setPlaylistDescription(remoteId, normalizedDescription)
                val error = result.exceptionOrNull()
                if (error != null && normalizedName != currentName) {
                    PlaylistChanges.notifyChanged(remoteId)
                    throw PlaylistPartiallyUpdatedException(normalizedName, error)
                }
                result.getOrThrow()
            }
        }
        updateLocalPlaylist(database, entity, normalizedName, normalizedDescription.ifEmpty { null })
        PlaylistChanges.notifyChanged(remoteId ?: entity?.id ?: id)
    }

suspend fun setStoredPlaylistCover(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
    coverUrl: String,
    playlist: PlaylistItem? = null,
): Result<Unit> =
    playlistMutation {
        val normalized = coverUrl.trim()
        require(normalized.isNotEmpty()) { "Playlist cover must not be empty" }
        val entity =
            database.withDatabaseTransaction {
                val current =
                    database.dao().findPlaylistEntity(id)
                        ?: unsavedRemotePlaylistEntity(id, playlist)
                current
                    .copy(
                        customThumbnailUrl = normalized,
                        lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                    ).also { database.dao().upsert(it) }
            }
        PlaylistChanges.notifyChanged(remotePlaylistId(entity, id) ?: entity.id)
    }

suspend fun uploadStoredPlaylistCover(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
    image: ByteArray,
    playlist: PlaylistItem? = null,
): Result<String?> =
    playlistMutation {
        require(image.isNotEmpty()) { "Playlist cover image must not be empty" }
        val entity = database.dao().findPlaylistEntity(id) ?: unsavedRemotePlaylistEntity(id, playlist)
        val isEditable = playlist?.canEdit ?: entity.isEditable
        check(isEditable) { "Playlist cover cannot be uploaded to a read-only playlist" }
        val remoteId = remotePlaylistId(entity, id) ?: throw IllegalStateException("Playlist is not synced")
        val remoteThumbnail = repository.setPlaylistThumbnail(remoteId, image).getOrThrow()
        val effective = remoteThumbnail?.takeIf { it.isNotBlank() }
        database.withDatabaseTransaction {
            val current = database.dao().findPlaylistEntity(entity.id) ?: entity
            database.dao().upsert(
                current.copy(
                    customThumbnailUrl = effective ?: current.customThumbnailUrl,
                    thumbnailUrl = effective ?: current.thumbnailUrl,
                    isEditable = isEditable,
                    lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                ),
            )
        }
        PlaylistChanges.notifyChanged(remoteId)
        effective
    }

private fun unsavedRemotePlaylistEntity(
    id: String,
    playlist: PlaylistItem?,
): PlaylistEntity =
    playlist?.takeUnless { it.isLocal }?.let {
        PlaylistEntity(
            id = id,
            name = it.title,
            browseId = id.removePrefix("VL"),
            thumbnailUrl = it.thumbnail,
            isEditable = it.isEditable,
            description = it.description,
            bookmarkedAt = null,
        )
    } ?: throw NoSuchElementException()

suspend fun removeStoredPlaylistCover(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
    playlist: PlaylistItem? = null,
): Result<String?> =
    playlistMutation {
        val stored = database.dao().findPlaylistEntity(id)
        val entity = stored ?: unsavedRemotePlaylistEntity(id, playlist)
        val isEditable = playlist?.canEdit ?: entity.isEditable
        val remoteId = remotePlaylistId(entity, id)
        val remoteThumbnail =
            if (remoteId != null && isEditable && repository.canManagePlaylists()) {
                repository.removePlaylistThumbnail(remoteId).getOrThrow()
            } else {
                null
            }
        val fallback = remoteThumbnail?.takeIf { it.isNotBlank() }
        database.withDatabaseTransaction {
            val current = database.dao().findPlaylistEntity(entity.id) ?: entity.takeIf { stored == null } ?: throw NoSuchElementException()
            database.dao().upsert(
                current.copy(
                    customThumbnailUrl = null,
                    thumbnailUrl = fallback ?: current.thumbnailUrl,
                    isEditable = isEditable,
                    lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                ),
            )
        }
        PlaylistChanges.notifyChanged(remoteId ?: entity.id)
        fallback
    }

suspend fun deleteStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    id: String,
): Result<Unit> =
    playlistMutation {
        val entity = database.dao().findPlaylistEntity(id)
        val remoteId = remotePlaylistId(entity, id)
        if (remoteId == null && entity == null) throw NoSuchElementException()
        val canManageRemote = remoteId != null && repository.canManagePlaylists()
        check(remoteId == null || canManageRemote || entity?.isEditable != true) {
            "Authentication is required to delete an owned YouTube playlist"
        }
        if (remoteId != null && canManageRemote) {
            repository.deletePlaylist(remoteId).getOrThrow()
        }
        database.withDatabaseTransaction {
            if (remoteId != null && canManageRemote) {
                LibraryDeletionTracker.trackDeletedPlaylist(id)
                LibraryDeletionTracker.trackDeletedPlaylist(remoteId)
            }
            if (entity != null) {
                database.dao().clearPlaylist(entity.id)
                database.dao().delete(entity)
            }
            val normalizedId = id.removePrefix("VL")
            database.dao().clearPlaylist(id)
            database.dao().clearPlaylist(normalizedId)
            database.dao().deletePlaylistById(normalizedId)
            database.dao().deletePlaylistById(id)
            database.dao().deletePlaylistById("VL$normalizedId")
        }
    }

internal fun remotePlaylistId(
    entity: PlaylistEntity?,
    requestedId: String,
): String? =
    when {
        entity != null && (entity.isLocal || entity.browseId == null) -> null
        entity == null && requestedId.startsWith("LP") -> null
        else -> (entity?.browseId ?: requestedId).removePrefix("VL")
    }

private suspend fun updateLocalPlaylist(
    database: MusicDatabase,
    entity: PlaylistEntity?,
    name: String,
    description: String?,
) {
    if (entity == null) return
    database.withDatabaseTransaction {
        val current = database.dao().findPlaylistEntity(entity.id) ?: return@withDatabaseTransaction
        database.dao().update(
            current.copy(
                name = name,
                description = description,
                lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
            ),
        )
        database.speedDialDao().updatePlaylistTitle(entity.id, name)
    }
}

// shortcut: Serialize membership checks and additions; use per-playlist locks if imports need parallelism.
private val playlistAddMutex = Mutex()

data class PlaylistAddBatchResult(
    val addedCount: Int,
    val failure: Throwable? = null,
)

suspend fun addSongsToLocalPlaylist(
    database: MusicDatabase,
    playlistId: String,
    songs: List<SongItem>,
    allowFirstDuplicate: Boolean = false,
): PlaylistAddBatchResult {
    currentCoroutineContext().ensureActive()
    return playlistAddMutex.withLock {
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val result =
                try {
                    database.withDatabaseTransaction {
                        val dao = database.dao()
                        val entity = checkNotNull(dao.findPlaylistEntity(playlistId))
                        check(entity.isEditable && remotePlaylistId(entity, playlistId) == null)
                        var position = dao.nextPlaylistPosition(entity.id)
                        val count = entity.remoteSongCount ?: dao.localPlaylistSongCount(entity.id)
                        var added = 0
                        var failure: Exception? = null
                        for (song in songs.take(100)) {
                            if (!(added == 0 && allowFirstDuplicate) && dao.checkInPlaylist(entity.id, song.id) > 0) {
                                failure = PlaylistSongAlreadyPresentException()
                                break
                            }
                            dao.upsertHistorySong(song)
                            dao.insert(PlaylistSongMap(playlistId = entity.id, songId = song.id, position = position++))
                            added++
                        }
                        if (added > 0) {
                            dao.update(
                                entity.copy(
                                    remoteSongCount = count + added,
                                    lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                                ),
                            )
                        }
                        PlaylistAddBatchResult(added, failure)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    PlaylistAddBatchResult(0, error)
                }
            if (result.addedCount > 0) PlaylistChanges.notifyChanged(playlistId)
            result
        }
    }
}

suspend fun addSongToStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    playlistId: String,
    song: SongItem,
    allowDuplicate: Boolean = false,
): Result<Unit> =
    playlistMutation {
        playlistAddMutex.withLock {
            val entity =
                database.dao().findPlaylistEntity(playlistId)
                    ?: database.dao().findPlaylistEntity(playlistId.removePrefix("VL"))
            val remoteId = remotePlaylistId(entity, playlistId)
            checkNotNull(entity)
            check(entity.isEditable)
            val targetLocalId = entity.id
            check(remoteId == null || repository.canManagePlaylists())
            if (!allowDuplicate && database.dao().checkInPlaylist(targetLocalId, song.id) > 0) {
                throw PlaylistSongAlreadyPresentException()
            }
            if (remoteId != null) {
                repository.addToPlaylist(remoteId, song.id, allowDuplicate = allowDuplicate, notifyChange = false).getOrThrow()
            }

            database.withDatabaseTransaction {
                val dao = database.dao()
                dao.upsertHistorySong(song)
                val currentEntity = checkNotNull(dao.findPlaylistEntity(targetLocalId))
                val nextPosition = dao.nextPlaylistPosition(targetLocalId)
                val currentCount = currentEntity.remoteSongCount ?: dao.localPlaylistSongCount(targetLocalId)
                dao.insert(
                    PlaylistSongMap(
                        playlistId = targetLocalId,
                        songId = song.id,
                        position = nextPosition,
                    ),
                )
                dao.update(
                    currentEntity.copy(
                        remoteSongCount = currentCount + 1,
                        lastUpdateTime = Clock.System.now().toLocalDateTime(TimeZone.UTC),
                    ),
                )
            }
            PlaylistChanges.notifyChanged(remoteId ?: targetLocalId)
        }
    }

suspend fun createStoredPlaylist(
    database: MusicDatabase,
    repository: YouTubeRepository,
    name: String,
): Result<String> =
    playlistMutation {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty())
        if (!repository.canManagePlaylists()) {
            return@playlistMutation createStoredLocalPlaylist(database, normalizedName).getOrThrow().id
        }
        val playlistId = repository.createPlaylist(normalizedName).getOrThrow()
        val now = Clock.System.now().toLocalDateTime(TimeZone.UTC)
        database.dao().insert(
            PlaylistEntity(
                name = normalizedName,
                browseId = playlistId.removePrefix("VL"),
                createdAt = now,
                lastUpdateTime = now,
                bookmarkedAt = now,
                isEditable = true,
                isAutoSync = true,
            ),
        )
        playlistId
    }

suspend fun createStoredLocalPlaylist(
    database: MusicDatabase,
    name: String,
): Result<PlaylistEntity> =
    playlistMutation {
        val normalizedName = name.trim()
        require(normalizedName.isNotEmpty())
        val entity = PlaylistEntity(name = normalizedName, isLocal = true)
        database.dao().insert(entity)
        entity
    }

private suspend fun <T> playlistMutation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
