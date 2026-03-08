@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.carousel.CarouselDefaults
import androidx.compose.material3.carousel.CarouselState
import androidx.compose.material3.carousel.HorizontalMultiBrowseCarousel
import androidx.compose.material3.carousel.rememberCarouselState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.metrolist.shared.constants.CarouselItemCornerRadius
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.more_options
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.utils.carouselScroll
import org.jetbrains.compose.resources.stringResource

@Composable
fun VideoShelf(
    items: List<YTItem>,
    onItemClick: (YTItem) -> Unit,
    onItemLongClick: ((YTItem) -> Unit)?,
    modifier: Modifier = Modifier,
    menuActionLabel: String = stringResource(Res.string.more_options),
    isWide: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(horizontal = if (isWide) 24.dp else 16.dp),
    currentSongId: State<String?> = remember { mutableStateOf(null) },
    isPlaying: State<Boolean> = remember { mutableStateOf(false) },
    isItemEnabled: (YTItem) -> Boolean = { true },
    onArtistClick: ((String) -> Unit)? = null,
    onAlbumClick: ((String) -> Unit)? = null,
    onItemsVisible: ((List<YTItem>) -> Unit)? = null,
    continuationContent: (@Composable () -> Unit)? = null,
    showDurationBadge: Boolean = false,
    state: CarouselState = rememberCarouselState { items.size + if (continuationContent != null) 1 else 0 },
) {
    // Material 3 1.13 defaults to one item per fling; keep momentum with snapping.
    val carouselFling = CarouselDefaults.multiBrowseFlingBehavior(state)
    HorizontalMultiBrowseCarousel(
        state = state,
        flingBehavior = carouselFling,
        preferredItemWidth = if (isWide) 320.dp else 280.dp,
        itemSpacing = 12.dp,
        contentPadding = PaddingValues(0.dp),
        modifier =
            modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .clip(RoundedCornerShape(CarouselItemCornerRadius))
                .carouselScroll(state, carouselFling),
    ) { index ->
        if (index == items.size) {
            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                continuationContent?.invoke()
            }
        } else {
            val item = items[index]
            val enabled = isItemEnabled(item)
            val playback by rememberItemPlayback(item.id, currentSongId, isPlaying)
            LaunchedEffect(item.id, (item as? SongItem)?.duration) { onItemsVisible?.invoke(listOf(item)) }
            YTGridItem(
                item = item,
                showDurationBadge = showDurationBadge,
                onClick = { onItemClick(item) },
                onLongClick = onItemLongClick?.let { callback -> { callback(item) } },
                menuActionLabel = menuActionLabel,
                onArtistClick = onArtistClick.takeIf { enabled },
                onAlbumClick = onAlbumClick.takeIf { enabled },
                fillWidth = true,
                artworkAspectRatio = 16f / 9f,
                artworkModifier = Modifier.maskClip(RoundedCornerShape(CarouselItemCornerRadius)),
                carouselItemDrawInfo = carouselItemDrawInfo,
                isCurrent = playback.isCurrent,
                isPlaying = playback.isPlaying,
                enabled = enabled,
                largeText = isWide,
                clickableMetadata = isWide && !LocalIsTv.current,
            )
        }
    }
}
