package com.metrolist.android.ui.utils

import androidx.compose.runtime.compositionLocalOf

/** Request to open/close the mini player queue overlay. */
val LocalMiniPlayerQueueRequest = compositionLocalOf<(() -> Unit)?> { null }
