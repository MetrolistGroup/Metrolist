/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.playback.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.AlbumArtistMap
import com.metrolist.music.db.entities.AlbumEntity
import com.metrolist.music.db.entities.ArtistEntity
import com.metrolist.music.db.entities.SongAlbumMap
import com.metrolist.music.db.entities.SongArtistMap
import com.metrolist.music.db.entities.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.apache.commons.lang3.RandomStringUtils
import timber.log.Timber
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalMediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) {
    private val _isScanning = MutableStateFlow(false)
    val isScanning = _isScanning.asStateFlow()

    private val artworkUri = Uri.parse("content://media/external/audio/albumart")

    suspend fun scan(): Result<Int> = withContext(Dispatchers.IO) {
        if (_isScanning.value) return@withContext Result.success(0)
        _isScanning.value = true

        try {
            val contentResolver: ContentResolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.DATE_MODIFIED,
                MediaStore.Audio.Media.TRACK,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.DATA,
            )

            // Select only music files with duration >= 10 seconds to exclude notification chimes / ringtones
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 10000"
            val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

            val scannedIds = mutableListOf<String>()
            var scannedCount = 0

            contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val yearColumn = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)
                val trackColumn = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dataColumn = cursor.getColumnIndex(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val mediaStoreId = cursor.getLong(idColumn)
                    val rawTitle = cursor.getString(titleColumn)?.trim()
                    val rawArtist = cursor.getString(artistColumn)?.trim()
                    val rawAlbum = cursor.getString(albumColumn)?.trim()
                    val rawAlbumId = cursor.getLong(albumIdColumn)
                    val durationMs = cursor.getLong(durationColumn)
                    val year = if (yearColumn != -1) cursor.getInt(yearColumn).takeIf { it > 0 } else null
                    val dateModifiedSec = if (dateModifiedColumn != -1) cursor.getLong(dateModifiedColumn) else 0L
                    val trackNumber = if (trackColumn != -1) cursor.getInt(trackColumn) else 0
                    val filePath = if (dataColumn != -1) cursor.getString(dataColumn) else null

                    val songId = "local:$mediaStoreId"
                    scannedIds.add(songId)

                    val title = if (!rawTitle.isNullOrBlank() && rawTitle != "<unknown>") {
                        rawTitle
                    } else if (!filePath.isNullOrBlank()) {
                        File(filePath).nameWithoutExtension
                    } else {
                        "Unknown Track"
                    }

                    val artistName = if (!rawArtist.isNullOrBlank() && rawArtist != "<unknown>") {
                        rawArtist
                    } else {
                        "Unknown Artist"
                    }

                    val albumName = if (!rawAlbum.isNullOrBlank() && rawAlbum != "<unknown>") {
                        rawAlbum
                    } else {
                        "Unknown Album"
                    }

                    val thumbnail = if (rawAlbumId > 0) {
                        ContentUris.withAppendedId(artworkUri, rawAlbumId).toString()
                    } else null

                    val durationSec = (durationMs / 1000).toInt().coerceAtLeast(1)
                    val dateModified = if (dateModifiedSec > 0) {
                        LocalDateTime.ofInstant(Instant.ofEpochSecond(dateModifiedSec), ZoneId.systemDefault())
                    } else {
                        LocalDateTime.now()
                    }

                    val localAlbumId = "local_album:$rawAlbumId"

                    // Synchronize or create artist, album, and song entities
                    database.query {
                        // 1. Artist
                        val existingArtist = artistByName(artistName)
                        val artistId = existingArtist?.id ?: ("LA" + RandomStringUtils.insecure().next(8, true, false)).also { newArtistId ->
                            insert(
                                ArtistEntity(
                                    id = newArtistId,
                                    name = artistName,
                                    isLocal = true,
                                    thumbnailUrl = thumbnail,
                                    lastUpdateTime = LocalDateTime.now(),
                                )
                            )
                        }

                        // 2. Album
                        val existingAlbum = getAlbumById(localAlbumId)
                        if (existingAlbum == null) {
                            insert(
                                AlbumEntity(
                                    id = localAlbumId,
                                    title = albumName,
                                    year = year,
                                    thumbnailUrl = thumbnail,
                                    songCount = 1,
                                    duration = durationSec,
                                    isLocal = true,
                                    lastUpdateTime = LocalDateTime.now(),
                                )
                            )
                            insert(
                                AlbumArtistMap(
                                    albumId = localAlbumId,
                                    artistId = artistId,
                                    order = 0,
                                )
                            )
                        }

                        // 3. Song
                        val existingSong = getSongEntityById(songId)
                        if (existingSong == null) {
                            insert(
                                SongEntity(
                                    id = songId,
                                    title = title,
                                    duration = durationSec,
                                    thumbnailUrl = thumbnail,
                                    albumId = localAlbumId,
                                    albumName = albumName,
                                    year = year,
                                    dateModified = dateModified,
                                    inLibrary = dateModified,
                                    isLocal = true,
                                    isDownloaded = false,
                                    isUploaded = false,
                                )
                            )
                            insert(
                                SongArtistMap(
                                    songId = songId,
                                    artistId = artistId,
                                    position = 0,
                                )
                            )
                            insert(
                                SongAlbumMap(
                                    songId = songId,
                                    albumId = localAlbumId,
                                    index = trackNumber,
                                )
                            )
                        } else {
                            // Update metadata if title/duration/thumbnail changed
                            update(
                                existingSong.copy(
                                    title = title,
                                    duration = durationSec,
                                    thumbnailUrl = thumbnail ?: existingSong.thumbnailUrl,
                                    albumId = localAlbumId,
                                    albumName = albumName,
                                    year = year ?: existingSong.year,
                                    dateModified = dateModified,
                                    isLocal = true,
                                )
                            )
                        }
                    }

                    scannedCount++
                }
            }

            // Cleanup removed files
            database.query {
                if (scannedIds.isNotEmpty()) {
                    deleteMissingLocalSongs(scannedIds)
                } else {
                    deleteAllLocalSongs()
                }
            }

            Timber.tag("LocalMediaScanner").i("Local media scan completed. Processed $scannedCount songs.")
            Result.success(scannedCount)
        } catch (e: Exception) {
            Timber.tag("LocalMediaScanner").e(e, "Failed to scan local media")
            Result.failure(e)
        } finally {
            _isScanning.value = false
        }
    }
}
