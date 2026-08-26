/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.models

import java.io.Serializable

data class PersistPlayerState(
    val playWhenReady: Boolean,
    val repeatMode: Int,
    val shuffleModeEnabled: Boolean,
    val volume: Float,
    val currentPosition: Long,
    val currentMediaItemIndex: Int,
    val playbackState: Int,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable
