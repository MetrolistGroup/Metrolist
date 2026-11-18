package com.metrolist.android.ui.glass

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.media3.common.Player
import com.metrolist.android.ui.theme.colorPalette
import com.metrolist.android.ui.player.ArtworkDisplayColors
import com.metrolist.android.ui.player.ArtworkDisplayResult
import com.metrolist.android.ui.player.SharedArtworkColors
import com.metrolist.android.ui.player.rememberArtworkDisplayResult
import com.metrolist.android.ui.theme.LocalPaletteFadeTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import timber.log.Timber

internal data class GlassArtwork(val mediaId: String?, val uri: String?)

/** Transparent artwork blends back to the live theme accent while its coverage fades out. */
@Immutable
internal data class GlassArtworkTint(val color: Color? = null, val darkness: Float = 0f) {
    val luminance: Float = color?.let { it.luminance() * it.alpha } ?: 0f

    fun lerpTo(target: GlassArtworkTint, fraction: Float): GlassArtworkTint {
        if (fraction == 0f) return this
        if (fraction == 1f) return target
        val from = color ?: target.color?.copy(alpha = 0f)
        val to = target.color ?: color?.copy(alpha = 0f)
        return GlassArtworkTint(
            color = if (from != null && to != null && from != to) lerp(from, to, fraction) else from,
            darkness = darkness + (target.darkness - darkness) * fraction,
        )
    }
}

/** Only Glass surfaces observe the frame, keeping navigation layout out of the animation. */
@Composable
internal fun PublishGlassArtworkTint(host: GlassMenuHostState, player: Player?, enabled: Boolean) {
    val appTint = LocalGlassArtworkTint.current
    if (appTint != null) {
        LaunchedEffect(host, appTint) {
            snapshotFlow { appTint.value }.collect { host.artworkTint = it }
        }
        return
    }
    val isDark = LocalPaletteFadeTarget.current?.colorPalette?.isDark ?: colorPalette().isDark
    val target = resolvedGlassArtworkTint(rememberGlassArtworkResult(player, enabled, isDark), enabled)
    val fraction = remember(host) { Animatable(1f) }
    val animate = enabled && isGlassMotionAllowed()
    LaunchedEffect(host, target, animate) {
        // The host already contains the artwork shown by the Dynamic provider.
        // Keep that frame while the fixed-theme provider resolves its first request.
        if (target == null) return@LaunchedEffect
        val current = host.artworkTint
        if (animate && current != target) {
            fraction.snapTo(0f)
            fraction.animateTo(1f, tween(350)) {
                host.artworkTint = current.lerpTo(target, value)
            }
        }
        host.artworkTint = target
    }
}

internal fun resolvedGlassArtworkTint(result: ArtworkDisplayResult, enabled: Boolean): GlassArtworkTint? {
    if (!enabled) return GlassArtworkTint()
    if (!result.resolved) return null
    val colors = result.colors
    return GlassArtworkTint(colors?.dominant, if (colors?.forceDark == true) 1f else 0f)
}

private fun Player.glassArtwork(): GlassArtwork {
    val item = currentMediaItem
    return GlassArtwork(
        item?.mediaId,
        (mediaMetadata.artworkUri ?: item?.mediaMetadata?.artworkUri)?.toString(),
    )
}

/** Observes metadata as well as queue transitions, including artwork replaced on the same song. */
@Composable
internal fun rememberGlassArtwork(player: Player?): GlassArtwork? {
    var artwork by remember(player) { mutableStateOf(player?.glassArtwork()) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(
                        Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_MEDIA_METADATA_CHANGED,
                        Player.EVENT_TIMELINE_CHANGED,
                    )) {
                    artwork = player.glassArtwork()
                }
            }
        }
        player?.addListener(listener)
        artwork = player?.glassArtwork()
        onDispose { player?.removeListener(listener) }
    }
    return artwork
}

/** Uses NZik's shared dominant-cover extraction rather than an unrelated vibrant accent. */
internal suspend fun extractGlassArtworkColor(bitmap: Bitmap, isDark: Boolean): Color? =
    SharedArtworkColors.fromBitmap(bitmap)?.display(isDark, keepDark = false)?.dominant

/** Keeps the current tint visible while the replacement cover loads. */
@Composable
fun rememberGlassArtworkColor(player: Player?, enabled: Boolean, isDark: Boolean): Color? {
    return rememberGlassArtworkDisplayColors(player, enabled, isDark)?.dominant
}

@Composable
internal fun rememberGlassArtworkDisplayColors(player: Player?, enabled: Boolean, isDark: Boolean): ArtworkDisplayColors? =
    rememberGlassArtworkResult(player, enabled, isDark).colors

@Composable
private fun rememberGlassArtworkResult(player: Player?, enabled: Boolean, isDark: Boolean): ArtworkDisplayResult {
    val artwork = rememberGlassArtwork(player)
    return rememberArtworkDisplayResult(
        artwork?.uri.takeIf { enabled }, isDark, awaitArtworkMetadata = enabled && artwork?.mediaId != null,
    )
}

@Composable
internal fun rememberGlassArtworkColor(
    artwork: GlassArtwork?,
    enabled: Boolean,
    isDark: Boolean,
    loadColor: suspend (GlassArtwork, Boolean) -> Color?,
): Color? {
    var color by remember(artwork, enabled, isDark) { mutableStateOf<Color?>(null) }
    LaunchedEffect(artwork, enabled, isDark) {
        if (!enabled || artwork?.uri == null) return@LaunchedEffect
        try {
            Timber.tag("GlassTintFlow").d("Glass artwork load: media=%s", artwork.mediaId)
            val loaded = loadColor(artwork, isDark)
            currentCoroutineContext().ensureActive()
            color = loaded
            Timber.tag("GlassTintFlow").d("Glass artwork ready: media=%s, color=%s",
                artwork.mediaId, loaded?.toArgb()?.toUInt()?.toString(16))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.tag("GlassArtwork").w(error, "Artwork tint unavailable")
        }
    }
    return color
}
