package com.metrolist.desktop.utils

import com.sun.jna.Platform
import dev.nucleusframework.media.control.MediaMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal fun Flow<Pair<String?, MediaMetadata>>.systemMediaMetadataUpdates(isLinux: Boolean = Platform.isLinux()): Flow<MediaMetadata> =
    distinctUntilChanged { previous, current ->
        // shortcut: Linux keeps the first metadata snapshot; remove after Nucleus preserves track IDs on updates.
        previous.first == current.first && (isLinux || previous.second == current.second)
    }.map { it.second }
