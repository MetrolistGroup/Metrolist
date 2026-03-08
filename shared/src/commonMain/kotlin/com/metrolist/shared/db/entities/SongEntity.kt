/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.db.entities

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import kotlinx.datetime.LocalDateTime

@Entity(
    tableName = "song",
    indices = [
        Index(value = ["inLibrary"]),
        Index(
            value = ["albumId"],
        ),
    ],
)
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(defaultValue = "NULL")
    val customTitle: String? = null,
    @ColumnInfo(defaultValue = "NULL")
    val customArtist: String? = null,
    val duration: Int = -1, // in seconds
    val thumbnailUrl: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    @ColumnInfo(defaultValue = "0")
    val explicit: Boolean = false,
    val year: Int? = null,
    val date: LocalDateTime? = null,
    val dateModified: LocalDateTime? = null,
    val liked: Boolean = false,
    val likedDate: LocalDateTime? = null,
    val totalPlayTime: Long = 0, // in milliseconds
    val inLibrary: LocalDateTime? = null,
    val dateDownload: LocalDateTime? = null,
    @ColumnInfo(name = "isLocal", defaultValue = "0")
    val isLocal: Boolean = false,
    val libraryAddToken: String? = null,
    val libraryRemoveToken: String? = null,
    @ColumnInfo(defaultValue = "0")
    val lyricsOffset: Int = 0,
    @ColumnInfo(defaultValue = "1")
    val romanizeLyrics: Boolean = true,
    @ColumnInfo(defaultValue = "0")
    val isDownloaded: Boolean = false,
    @ColumnInfo(name = "isUploaded", defaultValue = "0")
    val isUploaded: Boolean = false,
    @ColumnInfo(name = "isVideo", defaultValue = "0")
    val isVideo: Boolean = false,
    @ColumnInfo(name = "isEpisode", defaultValue = "0")
    val isEpisode: Boolean = false,
    @ColumnInfo(name = "playbackPosition", defaultValue = "NULL")
    val playbackPosition: Long? = null,
    @ColumnInfo(name = "uploadEntityId", defaultValue = "NULL")
    val uploadEntityId: String? = null,
    @ColumnInfo(name = "isCached", defaultValue = "0")
    val isCached: Boolean = false,
    @ColumnInfo(defaultValue = "NULL")
    val explicitness: Boolean? = null,
    @ColumnInfo(defaultValue = "0")
    val isUnavailable: Boolean = false,
)
