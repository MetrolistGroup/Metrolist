package com.metrolist.shared.api

import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeHttpException
import com.metrolist.innertubex.bodyAsTextLimited
import com.metrolist.innertubex.models.YouTubeClient
import com.metrolist.innertubex.models.response.BrowseResponse
import com.metrolist.innertubex.models.response.CreatePlaylistResponse
import com.metrolist.shared.auth.isExpiredYouTubeSessionResponse
import com.metrolist.shared.model.isEditablePlaylistId
import com.metrolist.shared.utils.Logger
import io.ktor.client.statement.HttpResponse
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

internal class YouTubePlaylistEdits(
    private val innerTube: InnerTube,
    private val invalidateExpiredSession: (InnerTube.SessionSnapshot) -> Unit,
    private val playlistContainsSong: suspend (String, String) -> Boolean,
    private val onPlaylistNotFound: suspend (String) -> Unit = {},
    private val onSongAdded: (InnerTube.SessionSnapshot, String, String) -> Unit = { _, _, _ -> },
) {
    private companion object {
        const val PLAYLIST_EDIT_THROTTLE_MS = 500L
    }

    private val playlistEditMutex = Mutex()
    private var lastPlaylistEditAtMs = 0L

    internal suspend fun runQueuedPlaylistEdit(block: suspend () -> Unit) {
        playlistEditMutex.withLock {
            val elapsed =
                kotlin.time.Clock.System
                    .now()
                    .toEpochMilliseconds() - lastPlaylistEditAtMs
            if (elapsed in 0L until PLAYLIST_EDIT_THROTTLE_MS) {
                delay((PLAYLIST_EDIT_THROTTLE_MS - elapsed).milliseconds)
            }
            try {
                block()
            } finally {
                lastPlaylistEditAtMs =
                    kotlin.time.Clock.System
                        .now()
                        .toEpochMilliseconds()
            }
        }
    }

    internal suspend fun runSessionPlaylistEdit(
        playlistId: String,
        requireEditableId: Boolean = true,
        reconcileNotFound: Boolean = true,
        block: suspend (InnerTube, String) -> Unit,
    ) {
        val session = innerTube.sessionSnapshot()
        val requestTube = innerTube.createIsolatedSession(true)
        requestTube.replaceSession(session.cookie, session.visitorData, session.dataSyncId, session.authUser, session.useLoginForBrowse)
        try {
            require(innerTube.hasSapCookieAuth(session))
            val id = playlistId.removePrefix("VL")
            if (requireEditableId) require(id.isEditablePlaylistId())
            runQueuedPlaylistEdit {
                if (innerTube.sessionSnapshot() != session) throw CancellationException("InnerTube session changed")
                try {
                    block(requestTube, id)
                } catch (error: Exception) {
                    if (innerTube.sessionSnapshot() != session) throw CancellationException("InnerTube session changed")
                    if (reconcileNotFound && id.isEditablePlaylistId() &&
                        (error is PlaylistNotFoundException || (error is InnerTubeHttpException && error.status.value == 404))
                    ) {
                        onPlaylistNotFound(id)
                        throw PlaylistNotFoundException(id)
                    }
                    throw error
                }
                if (innerTube.sessionSnapshot() != session) throw CancellationException("InnerTube session changed")
            }
        } finally {
            requestTube.close()
        }
    }

    fun canManagePlaylists(): Boolean = innerTube.hasSapCookieAuth()

    suspend fun setPlaylistSaved(
        playlistId: String,
        saved: Boolean,
    ): Result<Unit> =
        try {
            runSessionPlaylistEdit(playlistId, requireEditableId = false) { requestTube, id ->
                val response =
                    if (saved) {
                        requestTube.likePlaylist(YouTubeClient.WEB_REMIX, id)
                    } else {
                        requestTube.unlikePlaylist(YouTubeClient.WEB_REMIX, id)
                    }
                check(response.status.isSuccess())
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun createPlaylist(title: String): Result<String> =
        try {
            require(canManagePlaylists()) { "YouTube Music sign-in is required" }
            val normalizedTitle = title.trim()
            require(normalizedTitle.isNotEmpty()) { "Playlist name cannot be empty" }
            val response = innerTube.createPlaylist(YouTubeClient.WEB_REMIX, normalizedTitle)
            check(response.status.isSuccess()) { "Create playlist failed with HTTP ${response.status.value}" }
            Result.success(response.bodyLimited<CreatePlaylistResponse>(SMALL_RESPONSE_LIMIT).playlistId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun renamePlaylist(
        playlistId: String,
        title: String,
    ): Result<Unit> =
        try {
            require(canManagePlaylists()) { "YouTube Music sign-in is required" }
            val normalizedTitle = title.trim()
            require(normalizedTitle.isNotEmpty()) { "Playlist name cannot be empty" }
            runSessionPlaylistEdit(playlistId) { requestTube, id ->
                val response = requestTube.renamePlaylist(YouTubeClient.WEB_REMIX, id, normalizedTitle)
                if (response.status.value == 404) throw PlaylistNotFoundException(id)
                check(response.status.isSuccess()) { "Rename playlist failed with HTTP ${response.status.value}" }
                check(response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun setPlaylistDescription(
        playlistId: String,
        description: String,
    ): Result<Unit> =
        try {
            require(canManagePlaylists()) { "YouTube Music sign-in is required" }
            val normalizedDescription = description.trim()
            runSessionPlaylistEdit(playlistId) { requestTube, id ->
                val response = requestTube.setPlaylistDescription(YouTubeClient.WEB_REMIX, id, normalizedDescription)
                if (response.status.value == 404) throw PlaylistNotFoundException(id)
                check(response.status.isSuccess()) { "Set playlist description failed with HTTP ${response.status.value}" }
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun setPlaylistThumbnail(
        playlistId: String,
        image: ByteArray,
    ): Result<String?> =
        try {
            require(canManagePlaylists()) { "YouTube Music sign-in is required" }
            require(image.isNotEmpty()) { "Playlist cover image must not be empty" }
            var thumbnail: String? = null
            runSessionPlaylistEdit(playlistId, reconcileNotFound = false) { requestTube, id ->
                val response = requestTube.setPlaylistThumbnail(YouTubeClient.WEB_REMIX, id, image)
                check(response.status.isSuccess()) { "Set playlist cover failed with HTTP ${response.status.value}" }
                thumbnail = parseEditPlaylistThumbnail(response.bodyAsTextLimited(FEED_RESPONSE_LIMIT))
            }
            Result.success(thumbnail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun removePlaylistThumbnail(playlistId: String): Result<String?> =
        try {
            require(canManagePlaylists()) { "YouTube Music sign-in is required" }
            var thumbnail: String? = null
            runSessionPlaylistEdit(playlistId, reconcileNotFound = false) { requestTube, id ->
                val response = requestTube.removePlaylistThumbnail(YouTubeClient.WEB_REMIX, id)
                check(response.status.isSuccess()) { "Remove playlist cover failed with HTTP ${response.status.value}" }
                thumbnail = parseEditPlaylistThumbnail(response.bodyAsTextLimited(FEED_RESPONSE_LIMIT))
            }
            Result.success(thumbnail)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private fun parseEditPlaylistThumbnail(body: String): String? =
        try {
            val root = Json.parseToJsonElement(body).jsonObject
            val candidates =
                listOfNotNull(
                    root["newHeader"]?.jsonObject,
                    root["header"]?.jsonObject,
                    root,
                )
            candidates.firstNotNullOfOrNull(::largestThumbnailInTree)
        } catch (_: Exception) {
            null
        }

    private fun largestThumbnailInTree(element: JsonObject): String? {
        var bestUrl: String? = null
        var bestWidth = -1

        fun visitThumbnails(thumbnails: JsonArray) {
            thumbnails.forEach { item ->
                val obj = item.jsonObject
                val url = obj["url"]?.jsonPrimitive?.contentOrNull ?: return@forEach
                val width = obj["width"]?.jsonPrimitive?.intOrNull ?: 0
                if (width >= bestWidth) {
                    bestWidth = width
                    bestUrl = url
                }
            }
        }

        fun visit(node: kotlinx.serialization.json.JsonElement) {
            when (node) {
                is JsonObject -> {
                    node["thumbnails"]?.jsonArray?.let(::visitThumbnails)
                    node.values.forEach(::visit)
                }

                is JsonArray -> {
                    node.forEach(::visit)
                }

                else -> {
                }
            }
        }
        visit(element)
        return bestUrl
    }

    suspend fun deletePlaylist(playlistId: String): Result<Unit> =
        try {
            val requestSession = innerTube.sessionSnapshot()
            runQueuedPlaylistEdit {
                if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("InnerTube session changed")
                require(innerTube.hasSapCookieAuth(requestSession)) { "YouTube Music sign-in is required" }
                val ownershipResponse =
                    try {
                        innerTube.browse(
                            YouTubeClient.WEB_REMIX,
                            browseId = playlistId.toPlaylistBrowseId(),
                            setLogin = true,
                        )
                    } catch (e: InnerTubeHttpException) {
                        if (innerTube.sessionSnapshot() != requestSession) {
                            throw CancellationException("InnerTube session changed")
                        }
                        if (
                            isExpiredYouTubeSessionResponse(
                                e.status.value,
                                hasAuthenticatedSession = true,
                            )
                        ) {
                            invalidateExpiredSession(requestSession)
                            throw YouTubeSessionExpiredException()
                        }
                        if (e.status.value == 404) {
                            return@runQueuedPlaylistEdit
                        }
                        throw e
                    }
                if (innerTube.sessionSnapshot() != requestSession) {
                    throw CancellationException("InnerTube session changed")
                }
                if (
                    isExpiredYouTubeSessionResponse(
                        ownershipResponse.status.value,
                        hasAuthenticatedSession = true,
                    )
                ) {
                    invalidateExpiredSession(requestSession)
                    throw YouTubeSessionExpiredException()
                }
                if (ownershipResponse.status.value == 404) {
                    return@runQueuedPlaylistEdit
                }
                check(ownershipResponse.status.isSuccess()) {
                    "Resolve playlist ownership failed with HTTP ${ownershipResponse.status.value}"
                }
                val ownershipBody = ownershipResponse.bodyAsTextLimited(FEED_RESPONSE_LIMIT)
                repositoryJson.decodeFromString<BrowseResponse>(ownershipBody)
                val owned =
                    ownershipBody.contains("deletePlaylistEndpoint") ||
                        ownershipBody.contains("musicPlaylistEditHeaderRenderer")
                deleteRemotePlaylist(owned, playlistId, requestSession)
                if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("InnerTube session changed")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private suspend fun deleteRemotePlaylist(
        owned: Boolean,
        playlistId: String,
        requestSession: InnerTube.SessionSnapshot,
    ) {
        suspend fun runEndpoint(isDelete: Boolean): HttpResponse =
            if (isDelete) {
                innerTube.deletePlaylist(YouTubeClient.WEB_REMIX, playlistId, requestSession)
            } else {
                innerTube.unlikePlaylist(YouTubeClient.WEB_REMIX, playlistId, requestSession)
            }

        fun failRemoval(
            isDelete: Boolean,
            status: Int,
        ): Nothing =
            if (isDelete) {
                error("Delete playlist failed with HTTP $status")
            } else {
                throw PlaylistRemovalFailedException()
            }

        var isDelete = owned
        repeat(2) { attempt ->
            val response =
                try {
                    runEndpoint(isDelete)
                } catch (e: InnerTubeHttpException) {
                    if (
                        isExpiredYouTubeSessionResponse(
                            e.status.value,
                            hasAuthenticatedSession = true,
                        )
                    ) {
                        invalidateExpiredSession(requestSession)
                        throw YouTubeSessionExpiredException()
                    }
                    if (e.status.value == 404) return
                    if (attempt == 0 && e.status.value in 400..499) {
                        isDelete = !isDelete
                        return@repeat
                    }
                    failRemoval(isDelete, e.status.value)
                }
            if (
                isExpiredYouTubeSessionResponse(
                    response.status.value,
                    hasAuthenticatedSession = true,
                )
            ) {
                invalidateExpiredSession(requestSession)
                throw YouTubeSessionExpiredException()
            }
            if (response.status.value == 404) return
            if (response.status.isSuccess()) return
            if (attempt == 0 && response.status.value in 400..499) {
                isDelete = !isDelete
            } else {
                failRemoval(isDelete, response.status.value)
            }
        }
    }

    suspend fun addPlaylistToPlaylist(
        playlistId: String,
        sourcePlaylistId: String,
    ): Result<Unit> =
        try {
            val destination = playlistId.removePrefix("VL")
            val source = sourcePlaylistId.removePrefix("VL")
            require(source.isNotBlank() && destination.isNotBlank() && source != destination)
            runSessionPlaylistEdit(destination, reconcileNotFound = false) { requestTube, id ->
                val response = requestTube.addPlaylistToPlaylist(YouTubeClient.WEB_REMIX, id, source)
                check(response.status.isSuccess()) { "Copy playlist failed with HTTP ${response.status.value}" }
                check(response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun addToPlaylist(
        playlistId: String,
        videoId: String,
        allowDuplicate: Boolean = true,
    ): Result<Unit> =
        try {
            val requestSession = innerTube.sessionSnapshot()
            runSessionPlaylistEdit(playlistId) { requestTube, id ->
                require(innerTube.hasSapCookieAuth(requestSession))
                if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("InnerTube session changed")
                val alreadyPresent = !allowDuplicate && playlistContainsSong(playlistId, videoId)
                if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("InnerTube session changed")
                if (alreadyPresent) throw PlaylistSongAlreadyPresentException()
                val response = requestTube.addToPlaylist(YouTubeClient.WEB_REMIX, id, videoId)
                if (response.status.value == 404) throw PlaylistNotFoundException(id)
                check(response.status.isSuccess()) { "Add song failed with HTTP ${response.status.value}" }
                check(response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED")
                if (innerTube.sessionSnapshot() != requestSession) throw CancellationException("InnerTube session changed")
                onSongAdded(requestSession, id, videoId)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun movePlaylistSong(
        playlistId: String,
        setVideoId: String,
        successorSetVideoId: String?,
    ): Result<Unit> =
        try {
            runSessionPlaylistEdit(playlistId) { requestTube, id ->
                val response = requestTube.movePlaylistSong(YouTubeClient.WEB_REMIX, id, setVideoId, successorSetVideoId)
                if (response.status.value == 404) throw PlaylistNotFoundException(id)
                if (!response.status.isSuccess()) {
                    val text = response.bodyAsTextLimited(1024)
                    Logger.e(
                        "PlaylistEdits",
                        "movePlaylistSong failed HTTP ${response.status.value} (playlist=$playlistId, set=$setVideoId, succ=$successorSetVideoId): $text",
                    )
                    error("Move song failed with HTTP ${response.status.value}: $text")
                }
                check(response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e("PlaylistEdits", "movePlaylistSong exception", e)
            Result.failure(e)
        }

    suspend fun removePlaylistSong(
        playlistId: String,
        setVideoId: String,
        videoId: String,
    ): Result<Unit> =
        try {
            runSessionPlaylistEdit(playlistId) { requestTube, id ->
                val response = requestTube.removePlaylistSong(YouTubeClient.WEB_REMIX, id, setVideoId, videoId)
                if (response.status.value == 404) throw PlaylistNotFoundException(id)
                check(response.status.isSuccess()) { "Remove song failed with HTTP ${response.status.value}" }
                check(response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    suspend fun removePlaylistSongs(
        playlistId: String,
        songs: List<Pair<String, String>>,
    ): Result<Unit> =
        try {
            if (songs.isEmpty()) return Result.success(Unit)
            runSessionPlaylistEdit(playlistId) { requestTube, id ->
                val response = requestTube.removePlaylistSongs(YouTubeClient.WEB_REMIX, id, songs)
                if (response.status.value == 404) throw PlaylistNotFoundException(id)
                check(response.status.isSuccess()) { "Remove songs failed with HTTP ${response.status.value}" }
                check(response.bodyLimited<JsonObject>(SMALL_RESPONSE_LIMIT)["status"]?.jsonPrimitive?.contentOrNull == "STATUS_SUCCEEDED")
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
}
