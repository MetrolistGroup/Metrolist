/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.wrapped.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metrolist.music.R
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.graphics.graphicsLayer
import com.metrolist.music.ui.theme.bbhBartle
import kotlinx.coroutines.delay

private const val FADE_IN_DURATION = 1000
private const val SLIDE_IN_DURATION = 1000
private const val INITIAL_DELAY = 200
private const val ICON_DELAY = 200
private const val TITLE_DELAY = 400
private const val SUBTITLE_DELAY = 600
private const val BUTTON_DELAY = 1000
private val BOTTOM_PADDING = 64.dp

@Composable
fun AutoResizingText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle
) {
    var scaledTextStyle by remember { mutableStateOf(style) }
    var readyToDraw by remember { mutableStateOf(false) }

    Text(
        text = text,
        style = scaledTextStyle,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.drawWithContent {
            if (readyToDraw) {
                drawContent()
            }
        },
        onTextLayout = { textLayoutResult ->
            if (textLayoutResult.didOverflowWidth) {
                scaledTextStyle =
                    scaledTextStyle.copy(fontSize = scaledTextStyle.fontSize * 0.9)
            } else {
                readyToDraw = true
            }
        }
    )
}

@Composable
fun WrappedIntro(onNext: () -> Unit) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(INITIAL_DELAY.toLong())
        visible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "WrappedIntro bg")
        val scale by infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 3000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "intro scale"
        )
        val rotation by infiniteTransition.animateFloat(
            initialValue = -95f,
            targetValue = -85f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 5000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "intro rotation"
        )

        // Background "2025" text
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
        ) {
            BoxWithConstraints {
                AutoResizingText(
                    text = stringResource(id = R.string.wrapped_year),
                    style = TextStyle.Default.copy(
                        fontFamily = bbhBartle,
                        fontSize = 800.sp, // Increased size
                        color = Color.White,
                        drawStyle = Stroke(width = 2f)
                    ),
                    modifier = Modifier.width(this.maxHeight) // Use height for width due to rotation
                )
            }
        }



        // Main Content Column
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // App Icon
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = ICON_DELAY)) + slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = ICON_DELAY))
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.app_logo),
                    contentDescription = stringResource(id = R.string.wrapped_logo_content_description),
                    modifier = Modifier.size(100.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Metrolist Title with Layered Effect
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = TITLE_DELAY)) + slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = TITLE_DELAY))
            ) {
                Box {
                    val baseStyle = TextStyle(
                        fontFamily = bbhBartle,
                        textAlign = TextAlign.Center,
                        letterSpacing = 2.sp,
                        fontSize = 50.sp
                    )
                    AutoResizingText(text = stringResource(id = R.string.wrapped_intro_title), style = baseStyle.copy(color = Color.DarkGray), modifier = Modifier.offset(x = 2.dp, y = 2.dp))
                    AutoResizingText(text = stringResource(id = R.string.wrapped_intro_title), style = baseStyle.copy(color = Color.Gray), modifier = Modifier.offset(x = 1.dp, y = 1.dp))
                    AutoResizingText(text = stringResource(id = R.string.wrapped_intro_title), style = baseStyle.copy(color = Color.White))
                }
            }


            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = SUBTITLE_DELAY)) + slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = SUBTITLE_DELAY))
            ) {
                Text(
                    text = stringResource(id = R.string.wrapped_intro_subtitle),
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )
            }
        }

        // "Let's go!" Button at the bottom
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(FADE_IN_DURATION, delayMillis = BUTTON_DELAY)) + slideInVertically(animationSpec = tween(SLIDE_IN_DURATION, delayMillis = BUTTON_DELAY)) { it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = BOTTOM_PADDING)
        ) {
            Button(
                onClick = onNext,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White)
            ) {
                Text(
                    text = stringResource(id = R.string.wrapped_intro_button),
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
            }
        }
    }
}
