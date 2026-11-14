/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.constants

import android.os.Bundle
import androidx.media3.session.SessionCommand

object MediaSessionConstants {
    const val ACTION_TOGGLE_START_RADIO = "TOGGLE_START_RADIO"
    const val ACTION_ADD_TO_QUEUE = "ADD_TO_QUEUE"
    const val ACTION_TOGGLE_LIKE = "TOGGLE_LIKE"
    const val ACTION_TOGGLE_REPEAT = "TOGGLE_REPEAT"
    const val ACTION_TOGGLE_SHUFFLE = "TOGGLE_SHUFFLE"
    const val ACTION_ADD_TO_TARGET_PLAYLIST = "ADD_TO_TARGET_PLAYLIST"

    val CommandAddToQueue = SessionCommand(ACTION_ADD_TO_QUEUE, Bundle.EMPTY)
    val CommandToggleLike = SessionCommand(ACTION_TOGGLE_LIKE, Bundle.EMPTY)
    val CommandToggleStartRadio = SessionCommand(ACTION_TOGGLE_START_RADIO, Bundle.EMPTY)
    val CommandToggleRepeat = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
    val CommandToggleShuffle = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
    val CommandAddToTargetPlaylist = SessionCommand(ACTION_ADD_TO_TARGET_PLAYLIST, Bundle.EMPTY)
}
