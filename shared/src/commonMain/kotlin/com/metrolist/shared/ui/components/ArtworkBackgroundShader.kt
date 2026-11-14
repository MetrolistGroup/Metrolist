package com.metrolist.shared.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.PlatformContext
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.metrolist.shared.ui.player.LocalPlayerTrackArtwork
import com.metrolist.shared.ui.utils.nativeBlurCompat
import com.metrolist.shared.ui.utils.toImageBitmap
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.max

internal const val ARTWORK_TEXTURE_SIZE = 128
internal const val ARTWORK_TEXTURE_PADDING = 48

// Shared SkSL/AGSL: texture coordinates include the preblurred, transparent layer border.
internal val artworkBackgroundShader =
    """
    uniform shader artwork;
    uniform shader previous;
    uniform float2 viewport;
    uniform float2 ratios;
    uniform float3 cosines;
    uniform float3 sines;
    uniform float crossfade;
    uniform float liquid;
    uniform float4 crop;
    uniform float4 treatment;
    uniform float2 mirror;

    float2 unrotate(float2 p, float c, float s) {
        return float2(c*p.x + s*p.y, -s*p.x + c*p.y);
    }
    half4 over(half4 a, half4 b) { return a + b*(1.0-a.a); }
    half4 sampleArt(float2 uv, float old) {
        float2 p = uv*${ARTWORK_TEXTURE_SIZE}.0 + ${ARTWORK_TEXTURE_PADDING}.0;
        return old > 0.5 ? previous.eval(p) : artwork.eval(p);
    }
    half4 layers(float2 p, float ratio, float old, float alpha) {
        float d = max(viewport.x, viewport.y)*1.3;
        float2 pivot = float2(d*0.5);
        float2 offset = (viewport-float2(d))*0.5;
        float2 dimensions = float2(d*ratio, d);
        float2 a = (unrotate(p-offset-pivot, cosines.x, sines.x)+pivot)/dimensions;
        float2 b = (unrotate(p-offset+viewport*float2(0.95,0.7)-pivot, cosines.y, sines.y)+pivot)/dimensions;
        float2 q = unrotate(p-viewport*0.5, cosines.z, sines.z)+viewport*0.5;
        float2 c = (unrotate(q-offset+viewport*float2(0.5,-0.7)-pivot, cosines.z, sines.z)+pivot)/dimensions;
        // Keep child samples in separate temporaries for Skia's raster runtime.
        half4 first = sampleArt(a,old)*alpha;
        half4 second = sampleArt(b,old)*alpha;
        half4 third = sampleArt(c,old)*alpha;
        return third + (second + first*(1.0-second.a))*(1.0-third.a);
    }
    half4 main(float2 p) {
        if (liquid > 0.5) {
            p = (p-viewport*0.5)/1.3+viewport*0.5;
            half4 color = layers(p, ratios.x, 0.0, crossfade);
            if (crossfade < 1.0) color = over(color, layers(p, ratios.y, 1.0, 1.0-crossfade));
            color = over(half4(0.0,0.0,0.0,128.0/255.0), color);
            return over(half4(13.0/255.0),color);
        }
        // Below the seam the artwork is mirrored. The reflection starts identical to the sharp edge, then blurs more
        // with distance and settles into the bottom edge's muted average colour, so elements seem to leave the frame.
        half4 color;
        if (mirror.x > 0.0 && p.y > mirror.x) {
            float d = (p.y-mirror.x)/viewport.y;
            float spread = viewport.x*0.12*d;
            // The reflection recedes slowly, so only the strip next to the seam is reflected and distant content never bleeds in.
            float2 m = float2(p.x, mirror.x-0.4*(p.y-mirror.x));
            half4 reflection = half4(0.0);
            for (int i = 0; i < 12; i++) {
                float r = sqrt((float(i)+0.5)/12.0)*spread;
                float a = float(i)*2.39996;
                reflection += sampleArt((m+r*float2(cos(a),sin(a))-crop.xy)/crop.zw,0.0)*(1.0/12.0);
            }
            half4 edge = half4(0.0);
            for (int i = 0; i < 5; i++) {
                edge += sampleArt((float2(viewport.x*(0.1+0.2*float(i)),mirror.x)-crop.xy)/crop.zw,0.0)*0.2;
            }
            edge = half4(edge.rgb*0.88, edge.a);
            color = reflection + (edge-reflection)*smoothstep(0.0, 0.3, d);
        } else {
            color = sampleArt((p-crop.xy)/crop.zw,0.0);
        }
        color = over(half4(0.0,0.0,0.0,treatment.x),color);
        float f = p.y/viewport.y;
        if (treatment.y > 0.5) {
            float white = 0.12*max(0.0,1.0-f/0.52);
            float black = 0.42*max(0.0,(f-0.52)/0.48);
            color = over(half4(white,white,white,white),color);
            color = over(half4(0.0,0.0,0.0,black),color);
        }
        if (mirror.x > 0.0) {
            color *= clamp((p.y-mirror.y)/max(1.0,mirror.x-mirror.y),0.0,1.0);
        }
        return color;
    }
    """.trimIndent()

internal fun liquidRotationAngles(timeMs: Long): FloatArray =
    floatArrayOf(
        (-2.0 * PI * (timeMs % 130_000L) / 130_000.0).toFloat(),
        (2.0 * PI * (timeMs % 90_000L) / 90_000.0).toFloat(),
        (2.0 * PI * (timeMs % 70_000L) / 70_000.0).toFloat(),
    )

internal fun artworkBlurBucket(
    radiusPx: Float,
    displayedHeight: Float,
): Int = (ceil(radiusPx * ARTWORK_TEXTURE_SIZE / displayedHeight.coerceAtLeast(1f) / 2f).toInt() * 2).coerceIn(2, 16)

internal fun artworkLegacyBlurRadius(
    radiusPx: Float,
    displayedHeight: Float,
    textureHeight: Int,
): Int = (artworkBlurBucket(radiusPx, displayedHeight) * textureHeight.toFloat() / ARTWORK_TEXTURE_SIZE).toInt().coerceAtLeast(1)

internal data class ArtworkBackdropStyle(
    val blurRadiusPx: Float,
    val scrimAlpha: Float = 0f,
    val lyricsGradient: Boolean = false,
    val verticalBias: Float = 0f,
    val mirrorSeam: Float = 0f,
    val mirrorFadeStart: Float = 0f,
    val pendingScrimAlpha: Float = scrimAlpha,
    val cropOverride: FloatArray? = null,
)

internal fun artworkCrop(
    width: Float,
    height: Float,
    ratio: Float,
    style: ArtworkBackdropStyle,
): FloatArray {
    if (style.cropOverride != null) {
        return style.cropOverride
    }
    val imageHeight = if (style.mirrorSeam > 0f) height * style.mirrorSeam else max(height, width / ratio)
    val imageWidth = imageHeight * ratio
    return floatArrayOf(
        (width - imageWidth) / 2f,
        if (style.mirrorSeam >
            0f
        ) {
            0f
        } else {
            (height - imageHeight) * (1f + style.verticalBias) / 2f
        },
        imageWidth,
        imageHeight,
    )
}

@Composable
internal expect fun PlatformArtworkBackgroundCanvas(
    artwork: ImageBitmap,
    modifier: Modifier,
    style: ArtworkBackdropStyle,
)

@Composable
internal fun BlurredArtworkBackground(
    artwork: ImageBitmap,
    modifier: Modifier = Modifier,
    blurRadiusPx: Float,
    scrimAlpha: Float = 0f,
    lyricsGradient: Boolean = false,
    verticalBias: Float = 0f,
    mirrorSeam: Float = 0f,
    mirrorFadeStart: Float = 0f,
    pendingScrimAlpha: Float = scrimAlpha,
    cropOverride: FloatArray? = null,
) {
    PlatformArtworkBackgroundCanvas(
        artwork,
        modifier,
        ArtworkBackdropStyle(
            blurRadiusPx = blurRadiusPx,
            scrimAlpha = scrimAlpha,
            lyricsGradient = lyricsGradient,
            verticalBias = verticalBias,
            mirrorSeam = mirrorSeam,
            mirrorFadeStart = mirrorFadeStart,
            pendingScrimAlpha = pendingScrimAlpha,
            cropOverride = cropOverride,
        ),
    )
}

// Coil keys ignore size; add a discriminator so this reduced image cannot replace the full-size one.
internal fun artworkBackdropRequest(
    context: PlatformContext,
    url: String?,
): ImageRequest =
    ImageRequest
        .Builder(context)
        .data(url)
        .size(256, 256)
        .memoryCacheKeyExtra("artwork_backdrop", "256")
        .build()

@Composable
internal fun BlurredArtworkBackground(
    url: String?,
    modifier: Modifier = Modifier,
    blurRadiusPx: Float,
    scrimAlpha: Float = 0f,
    lyricsGradient: Boolean = false,
    verticalBias: Float = 0f,
    pendingScrimAlpha: Float = scrimAlpha,
) {
    val prepared = LocalPlayerTrackArtwork.current?.takeIf { it.url == url }
    if (prepared != null) {
        prepared.bitmap?.let {
            BlurredArtworkBackground(
                it,
                modifier,
                blurRadiusPx,
                scrimAlpha,
                lyricsGradient,
                verticalBias,
                pendingScrimAlpha = pendingScrimAlpha,
            )
        }
        return
    }
    val context = LocalPlatformContext.current
    val request = remember(url, context) { artworkBackdropRequest(context, url) }
    val painter = key(url) { rememberAsyncImagePainter(request) }
    val state by painter.state.collectAsState()
    val bitmap = remember(state) { (state as? AsyncImagePainter.State.Success)?.result?.image?.toImageBitmap() }
    bitmap?.let {
        BlurredArtworkBackground(
            it,
            modifier,
            blurRadiusPx,
            scrimAlpha,
            lyricsGradient,
            verticalBias,
            pendingScrimAlpha = pendingScrimAlpha,
        )
    }
}

@Composable
internal fun LegacyArtworkBackground(
    artwork: ImageBitmap,
    modifier: Modifier,
    style: ArtworkBackdropStyle,
) {
    Canvas(
        modifier
            .fillMaxSize()
            .graphicsLayer { if (style.mirrorSeam > 0f) compositingStrategy = CompositingStrategy.Offscreen }
            .nativeBlurCompat(style.blurRadiusPx, offscreen = style.mirrorSeam > 0f)
            .drawWithContent {
                drawContent()
                drawRect(Color.Black.copy(alpha = style.scrimAlpha))
                if (style.lyricsGradient) {
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.White.copy(alpha = 0.12f),
                            0.52f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.42f),
                        ),
                    )
                }
                if (style.mirrorSeam > 0f) {
                    drawRect(
                        Brush.verticalGradient(style.mirrorFadeStart to Color.Transparent, style.mirrorSeam to Color.Black),
                        blendMode = BlendMode.DstIn,
                    )
                }
            },
    ) {
        val crop = artworkCrop(size.width, size.height, artwork.width.toFloat() / artwork.height, style)

        fun DrawScope.image() {
            drawImage(
                artwork,
                dstOffset = IntOffset(crop[0].toInt(), crop[1].toInt()),
                dstSize = IntSize(crop[2].toInt().coerceAtLeast(1), crop[3].toInt().coerceAtLeast(1)),
            )
        }
        if (style.mirrorSeam > 0f) {
            val seam = size.height * style.mirrorSeam
            clipRect(top = seam) { withTransform({ scale(1f, -1f, Offset(size.width / 2f, seam)) }) { image() } }
        } else {
            image()
        }
    }
}
