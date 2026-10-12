/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.lyrics

import android.content.Context
import com.sydforum.musicplay.betterlyrics.BetterLyrics
import com.sydforum.musicplay.constants.EnableBetterLyricsKey
import com.sydforum.musicplay.utils.dataStore
import com.sydforum.musicplay.utils.get

object BetterLyricsProvider : LyricsProvider {
    override val name = "BetterLyrics"

    override fun isEnabled(context: Context): Boolean = context.dataStore[EnableBetterLyricsKey] ?: true

    override suspend fun getLyrics(
        context: Context,
        id: String,
        title: String,
        artist: String,
        duration: Int,
        album: String?,
    ): Result<String> = BetterLyrics.getLyrics(title, artist, duration, album)
}
