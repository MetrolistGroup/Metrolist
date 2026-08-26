/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.core.R
import com.metrolist.music.ui.screens.wrapped.components.AnimatedDecorativeElement
import com.metrolist.music.ui.theme.bbh_bartle
import kotlin.random.Random

@Composable
fun WrappedTotalArtistsScreen(
    uniqueArtistCount: Int,
    isVisible: Boolean
) {
    val animatedArtists = remember { Animatable(0f) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(isVisible, uniqueArtistCount) {
        if (isVisible && uniqueArtistCount > 0) {
            animatedArtists.animateTo(targetValue = uniqueArtistCount.toFloat(), animationSpec = tween(1500, easing = FastOutSlowInEasing))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.align(Alignment.TopStart)) {
            repeat(5) {
                AnimatedDecorativeElement(
                    Modifier.padding(start = (Random.nextInt(0, 150)).dp, top = (Random.nextInt(0, 150)).dp).size((Random.nextInt(20, 100)).dp),
                    isVisible
                )
            }
        }
        Box(modifier = Modifier.align(Alignment.BottomEnd)) {
            repeat(5) {
                AnimatedDecorativeElement(
                    Modifier.padding(end = (Random.nextInt(0, 150)).dp, bottom = (Random.nextInt(0, 150)).dp).size((Random.nextInt(20, 100)).dp),
                    isVisible
                )
            }
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(id = R.string.wrapped_total_artists_title),
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.headlineSmall.copy(color = Color.White, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(32.dp))
            BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val density = LocalDensity.current
                val baseStyle = MaterialTheme.typography.displayLarge.copy(
                    color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    fontFamily = bbh_bartle, drawStyle = Stroke(with(density) { 2.dp.toPx() })
                )
                val textStyle = remember(uniqueArtistCount, maxWidth) {
                    val finalText = uniqueArtistCount.toString()
                    var style = baseStyle.copy(fontSize = 96.sp)
                    var textWidth = textMeasurer.measure(finalText, style).size.width
                    while (textWidth > constraints.maxWidth) {
                        style = style.copy(fontSize = style.fontSize * 0.95f)
                        textWidth = textMeasurer.measure(finalText, style).size.width
                    }
                    style.copy(lineHeight = style.fontSize * 1.08f)
                }
                Text(
                    text = animatedArtists.value.toInt().toString(),
                    style = textStyle,
                    maxLines = 1,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(id = R.string.wrapped_total_artists_subtitle),
                modifier = Modifier.padding(horizontal = 24.dp),
                style = MaterialTheme.typography.bodyLarge.copy(color = Color.White.copy(alpha = 0.8f), textAlign = TextAlign.Center)
            )
        }
    }
}
