/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.ui.screens.wrapped.LocalWrappedManager
import com.metrolist.music.logic.wrapped.MessagePair
import com.metrolist.music.ui.theme.bbh_bartle
import kotlinx.coroutines.delay

@Composable
fun WrappedMinutesTease(
    messagePair: MessagePair?,
    onNavigateForward: () -> Unit,
    isDataReady: Boolean
) {
    val manager = LocalWrappedManager.current
    LaunchedEffect(Unit) {
        delay(3500)
        onNavigateForward()
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AnimatedVisibility(
            visible = messagePair != null && isDataReady,
            enter = fadeIn(tween(1000)) + scaleIn(initialScale = 0.9f, animationSpec = tween(1000))
        ) {
            Text(
                text = messagePair?.tease ?: "", modifier = Modifier.padding(horizontal = 24.dp),
                color = Color.White, fontSize = 30.sp, lineHeight = 34.sp, textAlign = TextAlign.Center,
                fontFamily = try { bbh_bartle } catch (e: Exception) { FontFamily.Default }
            )
        }
    }
}
