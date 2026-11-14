package com.metrolist.android

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.model.SongItem

/** Includes the area below the mini player without adding Scaffold padding. */
val LocalMiniPlayerBottomInset: ProvidableCompositionLocal<Dp> = compositionLocalOf { 0.dp }

/** Excludes transient expansion progress caused by resizing. */
val LocalPlayerExpanded: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

/** Includes expansion in progress so navigation can release focus. */
val LocalPlayerExpandedOrExpanding: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

val LocalListenTogetherManager: ProvidableCompositionLocal<ListenTogetherManager?> = compositionLocalOf { null }

/** Requires the app-root provider; reading outside it fails. */
val LocalPlaylistAdder: ProvidableCompositionLocal<(List<SongItem>) -> Unit> =
    compositionLocalOf { error("LocalPlaylistAdder was not provided") }
