/**
 * 7xTune native UI foundation.
 *
 * These primitives intentionally define composition, not application behaviour.
 * Playback, navigation and recommendation logic remain owned by the existing screens.
 */

package com.metrolist.music.ui.component

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import coil3.compose.AsyncImage
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.metrolist.music.R
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.YTItem
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.ui.theme.SevenXTunePalette

@Composable
fun PulseHomeHeader(
    accountName: String?,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val safeAccountName = accountName?.takeIf { it.isNotBlank() }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            SevenXTunePalette.SurfaceBright.copy(alpha = 0.96f),
                            SevenXTunePalette.Surface.copy(alpha = 0.94f),
                            SevenXTunePalette.MidnightSoft.copy(alpha = 0.98f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush =
                        Brush.linearGradient(
                            listOf(
                                SevenXTunePalette.Violet.copy(alpha = 0.42f),
                                SevenXTunePalette.ElectricBlue.copy(alpha = 0.16f),
                                SevenXTunePalette.PulsePink.copy(alpha = 0.26f),
                            ),
                        ),
                    shape = RoundedCornerShape(30.dp),
                )
                .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    SevenXTunePalette.Violet,
                                    SevenXTunePalette.ElectricBlue,
                                ),
                            ),
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "7x",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.sevenx_home_brand),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = SevenXTunePalette.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.sevenx_home_brand_kicker),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SevenXTunePalette.TextSecondary,
                )
            }

            Row(
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(
                            if (isPlaying) {
                                SevenXTunePalette.Violet.copy(alpha = 0.18f)
                            } else {
                                SevenXTunePalette.ElectricBlue.copy(alpha = 0.12f)
                            },
                        )
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPlaying) {
                                    SevenXTunePalette.PulsePink
                                } else {
                                    SevenXTunePalette.ElectricBlue
                                },
                            ),
                )
                Text(
                    text =
                        stringResource(
                            if (isPlaying) {
                                R.string.sevenx_home_status_playing
                            } else {
                                R.string.sevenx_home_status_ready
                            },
                        ),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SevenXTunePalette.TextPrimary,
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text =
                safeAccountName?.let {
                    stringResource(R.string.sevenx_home_greeting, it)
                } ?: stringResource(R.string.sevenx_home_guest_greeting),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SevenXTunePalette.TextPrimary,
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = stringResource(R.string.sevenx_home_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = SevenXTunePalette.TextSecondary,
        )
    }
}

@Composable
fun PulseSectionHeader(
    title: String,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier =
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(SevenXTunePalette.Violet),
        )
        Spacer(modifier = Modifier.size(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                Text(
                    text = eyebrow,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SevenXTunePalette.ElectricBlue,
                )
                Spacer(modifier = Modifier.height(1.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                color = SevenXTunePalette.TextPrimary,
            )
        }

        if (actionLabel != null && onActionClick != null) {
            Text(
                text = actionLabel,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onActionClick)
                        .background(SevenXTunePalette.Violet.copy(alpha = 0.12f))
                        .padding(horizontal = 11.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SevenXTunePalette.TextPrimary,
            )
        }
    }
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PulseSpeedDialHero(
    item: YTItem,
    isPinned: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(26.dp)

    Box(
        modifier =
            modifier
                .width(214.dp)
                .height(224.dp)
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .background(SevenXTunePalette.SurfaceRaised)
                .border(
                    1.dp,
                    Brush.linearGradient(
                        listOf(
                            SevenXTunePalette.Violet.copy(alpha = if (isActive) 0.72f else 0.26f),
                            SevenXTunePalette.ElectricBlue.copy(alpha = 0.18f),
                            SevenXTunePalette.PulsePink.copy(alpha = 0.18f),
                        ),
                    ),
                    shape,
                ),
    ) {
        AsyncImage(
            model = item.thumbnail?.resize(500, 500),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth(),
        )

        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(126.dp)
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.88f),
                            ),
                        ),
                    ),
        )

        if (isPinned) {
            Text(
                text = "PINNED",
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(11.dp)
                        .clip(RoundedCornerShape(50))
                        .background(SevenXTunePalette.Violet.copy(alpha = 0.86f))
                        .padding(horizontal = 9.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
            )
        }

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(14.dp),
        ) {
            Text(
                text = "FAST LANE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.ExtraBold,
                color = SevenXTunePalette.ElectricBlue,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (isPlaying) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "PLAYING NOW",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SevenXTunePalette.PulsePink,
                )
            }
        }

        if (isActive) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .align(Alignment.BottomCenter)
                        .background(
                            Brush.horizontalGradient(
                                listOf(
                                    SevenXTunePalette.Violet,
                                    SevenXTunePalette.ElectricBlue,
                                    SevenXTunePalette.PulsePink,
                                ),
                            ),
                        ),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PulseSpeedDialCompact(
    item: YTItem,
    isPinned: Boolean,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(19.dp)

    Row(
        modifier =
            modifier
                .width(208.dp)
                .height(96.dp)
                .clip(shape)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
                .background(SevenXTunePalette.SurfaceBright.copy(alpha = 0.88f))
                .border(
                    1.dp,
                    if (isActive) {
                        SevenXTunePalette.Violet.copy(alpha = 0.52f)
                    } else {
                        Color.White.copy(alpha = 0.07f)
                    },
                    shape,
                )
                .padding(9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        AsyncImage(
            model = item.thumbnail?.resize(180, 180),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier =
                Modifier
                    .size(78.dp)
                    .clip(
                        if (item is ArtistItem) {
                            CircleShape
                        } else {
                            RoundedCornerShape(15.dp)
                        },
                    ),
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isPlaying -> SevenXTunePalette.PulsePink
                                    isActive -> SevenXTunePalette.ElectricBlue
                                    else -> SevenXTunePalette.TextMuted
                                },
                            ),
                )
                if (isPinned) {
                    Text(
                        text = "PIN",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = SevenXTunePalette.Violet,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = SevenXTunePalette.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (isPlaying) {
                Text(
                    text = "PLAYING",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = SevenXTunePalette.PulsePink,
                )
            }
        }
    }
}

@Composable
fun PulseShelf(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actionLabel: String? = null,
    onActionClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        listOf(
                            SevenXTunePalette.SurfaceRaised.copy(alpha = 0.92f),
                            SevenXTunePalette.Surface.copy(alpha = 0.84f),
                        ),
                    ),
                )
                .border(
                    width = 1.dp,
                    brush =
                        Brush.horizontalGradient(
                            listOf(
                                SevenXTunePalette.Violet.copy(alpha = 0.18f),
                                SevenXTunePalette.ElectricBlue.copy(alpha = 0.08f),
                                SevenXTunePalette.Violet.copy(alpha = 0.14f),
                            ),
                        ),
                    shape = RoundedCornerShape(26.dp),
                )
                .padding(top = 4.dp, bottom = 8.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                if (eyebrow != null) {
                    Text(
                        text = eyebrow,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = SevenXTunePalette.ElectricBlue,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = SevenXTunePalette.TextPrimary,
                )
            }

            if (actionLabel != null && onActionClick != null) {
                Text(
                    text = actionLabel,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onActionClick)
                            .background(SevenXTunePalette.Violet.copy(alpha = 0.14f))
                            .border(
                                1.dp,
                                SevenXTunePalette.Violet.copy(alpha = 0.20f),
                                RoundedCornerShape(50),
                            )
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = SevenXTunePalette.TextPrimary,
                )
            }
        }

        content()
    }
}



@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PulseQuickPickCard(
    song: com.metrolist.innertube.models.SongItem,
    isActive: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val shape = RoundedCornerShape(22.dp)

    Column(
        modifier =
            modifier
                .width(170.dp)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.94f)
                    .clip(shape)
                    .background(SevenXTunePalette.SurfaceRaised),
        ) {
            AsyncImage(
                model = song.thumbnail.resize(400, 400),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .height(82.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(alpha = 0.80f),
                                ),
                            ),
                        ),
            )

            Row(
                modifier =
                    Modifier
                        .align(Alignment.BottomStart)
                        .padding(11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    isPlaying -> SevenXTunePalette.PulsePink
                                    isActive -> SevenXTunePalette.ElectricBlue
                                    else -> Color.White.copy(alpha = 0.70f)
                                },
                            ),
                )
                Text(
                    text = if (isPlaying) "PLAYING" else if (isActive) "ACTIVE" else "PICK",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                )
            }

            if (isActive) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .align(Alignment.BottomCenter)
                            .background(
                                Brush.horizontalGradient(
                                    listOf(
                                        SevenXTunePalette.Violet,
                                        SevenXTunePalette.ElectricBlue,
                                        SevenXTunePalette.PulsePink,
                                    ),
                                ),
                            ),
                )
            }
        }

        Spacer(modifier = Modifier.height(9.dp))

        Text(
            text = song.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = SevenXTunePalette.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        Text(
            text = song.artists.joinToString(" • ") { it.name },
            style = MaterialTheme.typography.bodySmall,
            color = SevenXTunePalette.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.88f),
        )
    }
}
