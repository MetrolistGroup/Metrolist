package com.metrolist.android.utils.toast

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class GlassToastMessage(
    val text: String,
    val durationMs: Long = 2000L,
    val id: String = System.nanoTime().toString()
)

class GlassToastDispatcher {
    fun enqueue(message: GlassToastMessage) {}
    fun dismiss(id: String) {}
}

val LocalGlassToastDispatcher = compositionLocalOf { GlassToastDispatcher() }

fun GlassToastDispatcher.enqueueGlassToast(text: String, durationMs: Long = 2000L) {
    enqueue(GlassToastMessage(text = text, durationMs = durationMs))
}

fun Context.enqueueGlassToast(text: String, durationMs: Long = 2000L) {}

val toastDurationMillis: Long = 2000L

class BottomNavigationToastPlacement(val bottomOffset: Dp = 72.dp)

fun trackToastObstruction(placement: BottomNavigationToastPlacement, height: Dp) {}
