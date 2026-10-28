/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.constants

import android.os.Bundle
import androidx.media3.session.SessionCommand

object MediaSessionConstants {
    const val ACTION_TOGGLE_LIBRARY = "TOGGLE_LIBRARY"
    const val ACTION_TOGGLE_START_RADIO = "TOGGLE_START_RADIO"
    const val ACTION_TOGGLE_LIKE = "TOGGLE_LIKE"
    const val ACTION_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
    const val ACTION_TOGGLE_REPEAT_MODE = "TOGGLE_REPEAT_MODE"
    const val ACTION_ADD_TO_TARGET_PLAYLIST = "ADD_TO_TARGET_PLAYLIST"
    const val ACTION_DOWNLOAD = "DOWNLOAD"

    const val KEY_SONG_ID = "songId"
    const val KEY_TITLE = "title"
    const val KEY_ARTIST = "artist"
    const val KEY_DURATION_MS = "durationMs"
    const val KEY_THUMBNAIL_URL = "thumbnailUrl"
    const val KEY_ALBUM_ID = "albumId"
    const val KEY_ALBUM_TITLE = "albumTitle"
    const val KEY_EXPLICIT = "explicit"

    val CommandToggleLibrary = SessionCommand(ACTION_TOGGLE_LIBRARY, Bundle.EMPTY)
    val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    val CommandToggleStartRadio = SessionCommand(ACTION_TOGGLE_START_RADIO, Bundle.EMPTY)
    val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    val CommandToggleRepeatMode = SessionCommand(ACTION_TOGGLE_REPEAT_MODE, Bundle.EMPTY)
    val CommandAddToTargetPlaylist = SessionCommand(ACTION_ADD_TO_TARGET_PLAYLIST, Bundle.EMPTY)
    val CommandDownload = SessionCommand(ACTION_DOWNLOAD, Bundle.EMPTY)

    const val TARGET_PLAYLIST_AUTO = "auto"
}
