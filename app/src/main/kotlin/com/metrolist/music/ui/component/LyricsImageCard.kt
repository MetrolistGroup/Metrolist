/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata

import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.toArgb
import androidx.palette.graphics.Palette
import coil3.ImageLoader
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun rememberAdjustedFontSize(
    text: String,
    maxWidth: Dp,
    maxHeight: Dp,
    density: Density,
    initialFontSize: TextUnit = 20.sp,
    minFontSize: TextUnit = 14.sp,
    style: TextStyle = TextStyle.Default,
    textMeasurer: androidx.compose.ui.text.TextMeasurer? = null
): TextUnit {
    val measurer = textMeasurer ?: rememberTextMeasurer()

    var calculatedFontSize by remember(text, maxWidth, maxHeight, style, density) {
        val initialSize = when {
            text.length < 50 -> initialFontSize
            text.length < 100 -> (initialFontSize.value * 0.8f).sp
            text.length < 200 -> (initialFontSize.value * 0.6f).sp
            else -> (initialFontSize.value * 0.5f).sp
        }
        mutableStateOf(initialSize)
    }

    LaunchedEffect(key1 = text, key2 = maxWidth, key3 = maxHeight) {
        val targetWidthPx = with(density) { maxWidth.toPx() * 0.92f }
        val targetHeightPx = with(density) { maxHeight.toPx() * 0.92f }
        if (text.isBlank()) {
            calculatedFontSize = minFontSize
            return@LaunchedEffect
        }

        if (text.length < 20) {
            val largerSize = (initialFontSize.value * 1.1f).sp
            val result = measurer.measure(
                text = AnnotatedString(text),
                style = style.copy(fontSize = largerSize)
            )
            if (result.size.width <= targetWidthPx && result.size.height <= targetHeightPx) {
                calculatedFontSize = largerSize
                return@LaunchedEffect
            }
        } else if (text.length < 30) {
            val largerSize = (initialFontSize.value * 0.9f).sp
            val result = measurer.measure(
                text = AnnotatedString(text),
                style = style.copy(fontSize = largerSize)
            )
            if (result.size.width <= targetWidthPx && result.size.height <= targetHeightPx) {
                calculatedFontSize = largerSize
                return@LaunchedEffect
            }
        }

        var minSize = minFontSize.value
        var maxSize = initialFontSize.value
        var bestFit = minSize
        var iterations = 0

        while (minSize <= maxSize && iterations < 20) {
            iterations++
            val midSize = (minSize + maxSize) / 2
            val midSizeSp = midSize.sp

            val result = measurer.measure(
                text = AnnotatedString(text),
                style = style.copy(fontSize = midSizeSp)
            )

            if (result.size.width <= targetWidthPx && result.size.height <= targetHeightPx) {
                bestFit = midSize
                minSize = midSize + 0.5f
            } else {
                maxSize = midSize - 0.5f
            }
        }

        calculatedFontSize = if (bestFit < minFontSize.value) minFontSize else bestFit.sp
    }

    return calculatedFontSize
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@Composable
fun LyricsImageCard(
    lyricText: String,
    mediaMetadata: MediaMetadata,
    darkBackground: Boolean = true,
    backgroundColor: Color? = null,
    backgroundStyle: LyricsBackgroundStyle = LyricsBackgroundStyle.SOLID,
    textColor: Color? = null,
    secondaryTextColor: Color? = null,
    textAlign: TextAlign = TextAlign.Center
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    val cardCornerRadius = 20.dp
    val padding = 28.dp
    val coverArtSize = 64.dp

    val defaultBgColor = if (darkBackground) Color(0xFF121212) else Color(0xFFF5F5F5)
    val backgroundSolidColor = backgroundColor ?: defaultBgColor
    
    val mainTextColor = textColor ?: if (darkBackground) Color.White else Color.Black
    val secondaryColor = secondaryTextColor ?: if (darkBackground) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)

    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(context)
            .data(mediaMetadata.thumbnailUrl)
            .crossfade(false)
            .build()
    )
    
    // Calculate gradient colors if needed
    var gradientBrush by remember { mutableStateOf<Brush?>(null) }
    
    if (backgroundStyle == LyricsBackgroundStyle.GRADIENT) {
        LaunchedEffect(mediaMetadata.thumbnailUrl) {
            withContext(Dispatchers.IO) {
                try {
                    val loader = ImageLoader(context)
                    val req = ImageRequest.Builder(context).data(mediaMetadata.thumbnailUrl).allowHardware(false).build()
                    val result = loader.execute(req)
                    val bmp = result.image?.toBitmap()
                    if (bmp != null) {
                        val palette = Palette.from(bmp).generate()
                        val vibrant = palette.getVibrantColor(defaultBgColor.toArgb())
                        val muted = palette.getMutedColor(defaultBgColor.toArgb())
                        val darkVibrant = palette.getDarkVibrantColor(defaultBgColor.toArgb())
                        
                        val color1 = Color(vibrant)
                        val color2 = Color(darkVibrant)
                        
                        gradientBrush = Brush.linearGradient(
                            colors = listOf(color1, color2),
                            tileMode = TileMode.Clamp
                        )
                    }
                } catch (_: Exception) {}
            }
        }
    }

    Box(
        modifier = Modifier
            .background(Color.Black) // Base background
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Background Layer
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            when (backgroundStyle) {
                LyricsBackgroundStyle.SOLID -> {
                    Box(modifier = Modifier.fillMaxSize().background(backgroundSolidColor))
                }
                LyricsBackgroundStyle.BLUR -> {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(50.dp) // High blur for background
                            .background(Color.Black.copy(alpha = 0.3f)) // Overlay to ensure text readability
                    )
                }
                LyricsBackgroundStyle.GRADIENT -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(gradientBrush ?: androidx.compose.ui.graphics.Brush.linearGradient(listOf(backgroundSolidColor, backgroundSolidColor)))
                    )
                }
            }
        }
    
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cardCornerRadius))
                // For the card itself, we can make it slightly transparent or match the background style
                // but usually the card IS the background cut out.
                // Here we simulate the card being transparent so the background shows through,
                // OR we redraw the background inside the card if we want the "card on background" look.
                // Based on previous code, the card had its own background.
                // Let's apply the same background logic to the card box.
        ) {
             when (backgroundStyle) {
                LyricsBackgroundStyle.SOLID -> {
                    Box(modifier = Modifier.fillMaxSize().background(backgroundSolidColor))
                }
                LyricsBackgroundStyle.BLUR -> {
                    // For blur, we want the card to be a window to the blurred background?
                    // Or have its own blurred background?
                    // Typically "Share Lyrics" looks like a card on a background.
                    // If we want the card to be seamless with the full image background, we can just use transparent.
                    // But to ensure it looks like the generated image:
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .blur(50.dp)
                            .background(Color.Black.copy(alpha = 0.3f))
                    )
                }
                LyricsBackgroundStyle.GRADIENT -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(gradientBrush ?: androidx.compose.ui.graphics.Brush.linearGradient(listOf(backgroundSolidColor, backgroundSolidColor)))
                    )
                }
            }
            
            // Border
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, mainTextColor.copy(alpha = 0.09f), RoundedCornerShape(cardCornerRadius))
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Header: Cover + Title/Artist aligned left
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Image(
                        painter = painter,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(coverArtSize)
                            .clip(RoundedCornerShape(3.dp))
                            .border(1.dp, mainTextColor.copy(alpha = 0.16f), RoundedCornerShape(3.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = mediaMetadata.title,
                            color = mainTextColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 2.dp)
                        )
                        Text(
                            text = mediaMetadata.artists.joinToString { it.name },
                            color = secondaryColor,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Lyrics text (centered)
                BoxWithConstraints(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    contentAlignment = when (textAlign) {
                        TextAlign.Left, TextAlign.Start -> Alignment.CenterStart
                        TextAlign.Right, TextAlign.End -> Alignment.CenterEnd
                        else -> Alignment.Center
                    }
                ) {
                    val availableWidth = maxWidth
                    val availableHeight = maxHeight
                    val textStyle = TextStyle(
                        color = mainTextColor,
                        fontWeight = FontWeight.Bold,
                        textAlign = textAlign,
                        letterSpacing = 0.005.em,
                    )

                    val textMeasurer = rememberTextMeasurer()
                    val initialSize = when {
                        lyricText.length < 50 -> 24.sp
                        lyricText.length < 100 -> 20.sp
                        lyricText.length < 200 -> 17.sp
                        lyricText.length < 300 -> 15.sp
                        else -> 13.sp
                    }

                    val dynamicFontSize = rememberAdjustedFontSize(
                        text = lyricText,
                        maxWidth = availableWidth - 8.dp,
                        maxHeight = availableHeight - 8.dp,
                        density = density,
                        initialFontSize = initialSize,
                        minFontSize = 18.sp,
                        style = textStyle,
                        textMeasurer = textMeasurer
                    )

                    Text(
                        text = lyricText,
                        style = textStyle.copy(
                            fontSize = dynamicFontSize,
                            lineHeight = dynamicFontSize.value.sp * 1.2f
                        ),
                        overflow = TextOverflow.Ellipsis,
                        textAlign = textAlign,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                // Footer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(50))
                            .background(secondaryColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.small_icon),
                            contentDescription = null,
                            modifier = Modifier
                                .size(38.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = stringResource(R.string.app_name),
                        color = secondaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

