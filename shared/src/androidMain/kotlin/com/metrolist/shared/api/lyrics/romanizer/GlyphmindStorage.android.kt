/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.youtube.playback.AndroidContextProvider
import java.io.File

internal actual fun glyphmindStorageDirectory(): String =
    File(AndroidContextProvider.applicationContext.noBackupFilesDir, "glyphmind").absolutePath

internal actual fun removeLegacyJapaneseDictionary() {
    File(AndroidContextProvider.applicationContext.noBackupFilesDir, "japanese-dictionary").deleteRecursively()
}
