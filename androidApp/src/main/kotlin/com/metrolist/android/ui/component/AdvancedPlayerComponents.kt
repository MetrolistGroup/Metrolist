/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.component

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.metrolist.android.listentogether.AndroidListenTogetherManager
import com.metrolist.shared.ui.components.SwipeGestureHandler as SharedSwipeGestureHandler

@Composable
fun SwipeGestureHandler(
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    removeOnSwipeLeft: Boolean = false,
    enableSwipeRight: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isGuest by AndroidListenTogetherManager.isGuestPlaybackLockedFlow.collectAsState()
    SharedSwipeGestureHandler(
        onSwipeLeft = onSwipeLeft,
        onSwipeRight = onSwipeRight,
        onFeedback = { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        },
        modifier = modifier,
        enabled = enabled && !isGuest,
        removeOnSwipeLeft = removeOnSwipeLeft,
        enableSwipeRight = enableSwipeRight,
        content = content,
    )
}
