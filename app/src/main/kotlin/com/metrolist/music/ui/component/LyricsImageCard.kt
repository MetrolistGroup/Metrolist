/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import android.annotation.SuppressLint
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.metrolist.music.R
import com.metrolist.music.models.MediaMetadata

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
    textColor: Color? = null,
    secondaryTextColor: Color? = null,
    textAlign: TextAlign = TextAlign.Center
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // حجم الكارد المربع (الأقل من العرض/الطول)
    val cardSizeDp = remember {
        340.dp // يمكنك تعديله حسب الحاجة أو جعله متغيراً
    }
    val cardCornerRadius = 20.dp
    val padding = 28.dp
    val coverArtSize = 64.dp

    val backgroundGradient = backgroundColor ?: if (darkBackground) Color(0xFF121212) else Color(0xFFF5F5F5)
    val mainTextColor = textColor ?: if (darkBackground) Color.White else Color.Black
    val secondaryColor = secondaryTextColor ?: if (darkBackground) Color.White.copy(alpha = 0.7f) else Color.Black.copy(alpha = 0.7f)

    val painter = rememberAsyncImagePainter(
        ImageRequest.Builder(context)
            .data(mediaMetadata.thumbnailUrl)
            .crossfade(false)
            .build()
    )

    Box(
        modifier = Modifier
            .background(backgroundGradient)
            .fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(cardSizeDp)
                .clip(RoundedCornerShape(cardCornerRadius))
                .background(backgroundGradient)
                .border(1.dp, mainTextColor.copy(alpha = 0.09f), RoundedCornerShape(cardCornerRadius)),
            contentAlignment = Alignment.Center
        ) {
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
                            .size(22.dp)
                            .clip(RoundedCornerShape(50))
                            .background(secondaryColor),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.small_icon),
                            contentDescription = null,
                            modifier = Modifier
                                .size(16.dp),
                            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(backgroundGradient) // الرمز بلون الخلفية
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = context.getString(R.string.app_name),
                        color = secondaryColor,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
