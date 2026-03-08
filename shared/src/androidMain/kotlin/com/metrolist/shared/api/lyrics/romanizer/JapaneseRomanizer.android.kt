package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.youtube.playback.AndroidContextProvider
import java.io.File

internal actual fun japaneseDictionaryStorageDirectory(): File =
    File(AndroidContextProvider.applicationContext.noBackupFilesDir, "japanese-dictionary")
