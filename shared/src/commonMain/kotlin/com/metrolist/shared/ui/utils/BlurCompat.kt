package com.metrolist.shared.ui.utils

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal expect val renderEffectBlurSupported: Boolean

fun supportsNativeBlur(): Boolean = renderEffectBlurSupported

fun Modifier.nativeBlurCompat(
    radiusPx: Float,
    offscreen: Boolean = false,
    enabled: Boolean = true,
): Modifier =
    if (enabled && supportsNativeBlur() && radiusPx > 0f) {
        graphicsLayer {
            renderEffect = BlurEffect(radiusPx, radiusPx, TileMode.Clamp)
            if (offscreen) {
                compositingStrategy = CompositingStrategy.Offscreen
            }
        }
    } else {
        this
    }

fun Modifier.offscreenCompat(): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }

internal const val PROGRESSIVE_BLUR_H_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float startY;
uniform float endY;

float getIntensity(float y) {
    float fraction = clamp((y - startY) / max(endY - startY, 1.0), 0.0, 1.0);
    return fraction * fraction * (3.0 - 2.0 * fraction);
}

float4 blurH(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(k * stepDist, 0.0);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.y);
    return half4(blurH(coord, radius));
}
"""

internal const val PROGRESSIVE_BLUR_V_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float startY;
uniform float endY;

float getIntensity(float y) {
    float fraction = clamp((y - startY) / max(endY - startY, 1.0), 0.0, 1.0);
    return fraction * fraction * (3.0 - 2.0 * fraction);
}

float4 blurV(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(0.0, k * stepDist);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.y);
    return half4(blurV(coord, radius));
}
"""

internal const val PROGRESSIVE_BLUR_HORIZONTAL_H_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float leftStartX;
uniform float leftEndX;
uniform float rightStartX;
uniform float rightEndX;

float getIntensity(float x) {
    float left = 0.0;
    if (leftEndX > leftStartX) {
        float f = clamp((leftEndX - x) / max(leftEndX - leftStartX, 1.0), 0.0, 1.0);
        left = f * f * (3.0 - 2.0 * f);
    }
    float right = 0.0;
    if (rightEndX > rightStartX) {
        float f = clamp((x - rightStartX) / max(rightEndX - rightStartX, 1.0), 0.0, 1.0);
        right = f * f * (3.0 - 2.0 * f);
    }
    return max(left, right);
}

float4 blurH(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(k * stepDist, 0.0);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.x);
    return half4(blurH(coord, radius));
}
"""

internal const val PROGRESSIVE_BLUR_HORIZONTAL_V_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float leftStartX;
uniform float leftEndX;
uniform float rightStartX;
uniform float rightEndX;

float getIntensity(float x) {
    float left = 0.0;
    if (leftEndX > leftStartX) {
        float f = clamp((leftEndX - x) / max(leftEndX - leftStartX, 1.0), 0.0, 1.0);
        left = f * f * (3.0 - 2.0 * f);
    }
    float right = 0.0;
    if (rightEndX > rightStartX) {
        float f = clamp((x - rightStartX) / max(rightEndX - rightStartX, 1.0), 0.0, 1.0);
        right = f * f * (3.0 - 2.0 * f);
    }
    return max(left, right);
}

float4 blurV(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(0.0, k * stepDist);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.x);
    return half4(blurV(coord, radius));
}
"""

internal const val PROGRESSIVE_BLUR_VERTICAL_H_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float topStartY;
uniform float topEndY;
uniform float bottomStartY;
uniform float bottomEndY;

float getIntensity(float y) {
    float top = 0.0;
    if (topEndY > topStartY) {
        float f = clamp((topEndY - y) / max(topEndY - topStartY, 1.0), 0.0, 1.0);
        top = f * f * (3.0 - 2.0 * f);
    }
    float bottom = 0.0;
    if (bottomEndY > bottomStartY) {
        float f = clamp((y - bottomStartY) / max(bottomEndY - bottomStartY, 1.0), 0.0, 1.0);
        bottom = f * f * (3.0 - 2.0 * f);
    }
    return max(top, bottom);
}

float4 blurH(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(k * stepDist, 0.0);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.y);
    return half4(blurH(coord, radius));
}
"""

internal const val PROGRESSIVE_BLUR_VERTICAL_V_SKSL = """
uniform shader content;
uniform float blurRadius;
uniform float4 crop;
uniform float topStartY;
uniform float topEndY;
uniform float bottomStartY;
uniform float bottomEndY;

float getIntensity(float y) {
    float top = 0.0;
    if (topEndY > topStartY) {
        float f = clamp((topEndY - y) / max(topEndY - topStartY, 1.0), 0.0, 1.0);
        top = f * f * (3.0 - 2.0 * f);
    }
    float bottom = 0.0;
    if (bottomEndY > bottomStartY) {
        float f = clamp((y - bottomStartY) / max(bottomEndY - bottomStartY, 1.0), 0.0, 1.0);
        bottom = f * f * (3.0 - 2.0 * f);
    }
    return max(top, bottom);
}

float4 blurV(float2 coord, float radius) {
    if (radius <= 0.001) {
        return content.eval(coord);
    }

    float4 result = content.eval(coord);
    float stepDist = radius / 10.0;

    for (float k = 1.0; k <= 10.0; k += 1.0) {
        float weight = exp(-k * k * 0.03462604);
        float2 offset = float2(0.0, k * stepDist);
        float2 coord1 = clamp(coord - offset, crop.xy, crop.zw);
        float2 coord2 = clamp(coord + offset, crop.xy, crop.zw);
        result += (content.eval(coord1) + content.eval(coord2)) * weight;
    }

    return result / 9.47198;
}

half4 main(float2 coord) {
    float radius = blurRadius * getIntensity(coord.y);
    return half4(blurV(coord, radius));
}
"""

@Composable
expect fun Modifier.queueVerticalBlur(
    blurHeight: Dp = 95.dp,
    maxRadius: Dp = 20.dp,
    enabled: Boolean = true,
): Modifier

@Composable
expect fun Modifier.artworkHorizontalProgressiveBlur(
    leftStartFraction: Float = 0f,
    leftEndFraction: Float = 0f,
    rightStartFraction: Float = 0.80f,
    rightEndFraction: Float = 1.0f,
    maxRadius: Dp = 36.dp,
    enabled: Boolean = true,
): Modifier

@Composable
expect fun Modifier.artworkVerticalProgressiveBlur(
    topStartFraction: Float = 0f,
    topEndFraction: Float = 0f,
    bottomStartFraction: Float = 0.55f,
    bottomEndFraction: Float = 0.70f,
    maxRadius: Dp = 56.dp,
    enabled: Boolean = true,
): Modifier
