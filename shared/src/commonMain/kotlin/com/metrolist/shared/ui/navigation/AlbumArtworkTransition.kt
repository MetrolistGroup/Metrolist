package com.metrolist.shared.ui.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.carousel.CarouselItemDrawInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.components.CollectionPreviews
import kotlin.math.abs
import kotlin.uuid.Uuid

val LocalAlbumArtworkSource = staticCompositionLocalOf<String?> { null }

class AlbumArtworkSource(
    private val album: AlbumItem,
    val token: String,
    private val navigate: () -> Unit,
) {
    fun select() {
        CollectionPreviews.record(album)
        navigate()
    }

    @Composable
    fun modifier(
        cornerRadius: Dp,
        carouselItem: CarouselItemDrawInfo? = null,
    ): Modifier =
        Modifier
            .navigationPageSource(token)
            .albumArtworkTransition(album.id, AlbumArtworkRole.Card, cornerRadius, token, carouselItem)
}

@Composable
fun rememberAlbumArtworkSource(
    item: YTItem,
    onClick: (() -> Unit)?,
): AlbumArtworkSource? {
    if (onClick == null || item !is AlbumItem ||
        navigationPattern(LocalNavigationRoute.current, Screen.Album(item.id)) != NavigationPattern.Expressive
    ) {
        return null
    }
    val token = remember(item.id) { Uuid.random().toString() }
    return AlbumArtworkSource(item, token, onClick)
}

enum class AlbumArtworkRole { Card, Header }

internal fun albumArtworkKey(
    albumId: String,
    source: String,
): String = "album-artwork-$albumId:$source"

@OptIn(ExperimentalSharedTransitionApi::class)
private class ScrollContainerOverlayClip(
    private val overlay: NavOverlayCoordinates,
) : SharedTransitionScope.OverlayClip {
    var element: LayoutCoordinates? = null
    var carouselItem: CarouselItemDrawInfo? = null
    var cornerRadius: () -> Dp = { 0.dp }

    override fun getClipPath(
        sharedContentState: SharedTransitionScope.SharedContentState,
        bounds: Rect,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Path? {
        val root = overlay.coordinates?.takeIf { it.isAttached } ?: return null
        val resting = element?.takeIf { it.isAttached } ?: return null
        val full = root.localBoundingBoxOf(resting, clipBounds = false)
        val mask = carouselItem?.maskRect
        val visible =
            root.localBoundingBoxOf(resting).let { visible ->
                if (mask == null) visible else visible.intersect(full.copy(left = full.left + mask.left, right = full.left + mask.right))
            }
        val distance =
            maxOf(
                abs(bounds.left - full.left),
                abs(bounds.top - full.top),
                abs(bounds.right - full.right),
                abs(bounds.bottom - full.bottom),
            )
        val content = overlay.contentCoordinates?.takeIf { it.isAttached }?.let { root.localBoundingBoxOf(it) }
        val clip = content?.let { visible.inflate(distance).intersect(it) } ?: visible.inflate(distance)
        if (clip.isEmpty) return Path()
        if (mask == null) return Path().apply { addRect(clip) }
        val radius = with(density) { CornerRadius(cornerRadius().toPx()) }
        return Path().apply { addRoundRect(RoundRect(clip, radius)) }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.albumArtworkTransition(
    albumId: String,
    role: AlbumArtworkRole,
    cornerRadius: Dp,
    source: String? = LocalAlbumArtworkSource.current,
    carouselItem: CarouselItemDrawInfo? = null,
): Modifier {
    val scope = LocalNavSharedTransitionScope.current
    val visibility = LocalNavVisibilityScope.current
    val overlay = LocalNavOverlayCoordinates.current
    val containers = LocalNavigationContainerState.current
    val route = LocalNavigationRoute.current
    if (source == null || scope == null || visibility == null || overlay == null ||
        !rememberNavigationMotion().enabled
    ) {
        return clip(RoundedCornerShape(cornerRadius))
    }
    val key = albumArtworkKey(albumId, source)
    DisposableEffect(containers, key, role, route) {
        onDispose {
            if (role == AlbumArtworkRole.Card && containers != null) {
                val openingAlbum = containers.change?.takeUnless { it.isPop }?.destination as? Screen.Album
                val openingKey = openingAlbum?.artworkSource?.let { albumArtworkKey(openingAlbum.id, it) }
                if (containers.change?.destination == route || openingKey != key) {
                    containers.albumArtworkVisibility.remove(key)
                }
            }
        }
    }
    val visibilityModifier =
        if (role == AlbumArtworkRole.Card) {
            Modifier.onGloballyPositioned { coordinates ->
                if (containers != null && (containers.change == null || containers.change?.destination == route)) {
                    val root = overlay.contentCoordinates?.takeIf { it.isAttached } ?: overlay.coordinates?.takeIf { it.isAttached }
                    val bounds = root?.localBoundingBoxOf(coordinates)
                    containers.albumArtworkVisibility[key] = bounds != null && !bounds.isEmpty && carouselItem?.maskRect?.isEmpty != true
                }
            }
        } else {
            Modifier
        }
    if (containers?.elementPattern != NavigationPattern.Expressive) {
        return then(visibilityModifier).clip(RoundedCornerShape(cornerRadius))
    }
    val other = if (role == AlbumArtworkRole.Card) AlbumArtworkRole.Header else AlbumArtworkRole.Card
    DisposableEffect(overlay, key, role, cornerRadius) {
        overlay.radii["$key/$role"] = cornerRadius
        onDispose { overlay.radii.remove("$key/$role") }
    }
    val otherRadius = overlay.radii["$key/$other"] ?: cornerRadius
    val spatialSpec = MotionScheme.expressive().defaultSpatialSpec<Dp>()
    val radius =
        visibility.transition.animateDp(
            transitionSpec = { spatialSpec },
            label = "albumArtworkCorner",
        ) { state -> if (state == EnterExitState.Visible) cornerRadius else otherRadius }
    val overlayClip = remember(overlay) { ScrollContainerOverlayClip(overlay) }
    overlayClip.carouselItem = carouselItem
    overlayClip.cornerRadius = { radius.value }
    return then(visibilityModifier)
        .onPlaced { overlayClip.element = it }
        .expressiveNavigationElement(key, RoundedCornerShape(cornerRadius), overlayClip)
        .graphicsLayer {
            shape = RoundedCornerShape(radius.value)
            this.clip = true
        }
}
