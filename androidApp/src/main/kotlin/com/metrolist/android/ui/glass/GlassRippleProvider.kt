package com.metrolist.android.ui.glass

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.Box
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier

internal val LocalGlassRippleActive = staticCompositionLocalOf { false }

private class NativeRippleLocals(
    val configuration: RippleConfiguration?,
    val indication: Indication,
)

private val LocalNativeRippleLocals = compositionLocalOf<NativeRippleLocals?> { null }

/** Changes only indication styling, leaving native click, long-click and scroll handling intact. */
@Composable
fun GlassRippleProvider(
    settings: GlassSettings = rememberGlassSettings(),
    content: @Composable () -> Unit,
) {
    // Keep the content at the same composition position when the master changes: the
    // root content owns navigation, search and scroll state. OFF forwards native values.
    // A nested native surface must recover the original values if its parent supplies Glass ripple.
    val nativeConfiguration = LocalRippleConfiguration.current
    val nativeIndication = LocalIndication.current
    val inheritedNative = LocalNativeRippleLocals.current
    val native = if (LocalGlassRippleActive.current && inheritedNative != null) inheritedNative
        else remember(nativeConfiguration, nativeIndication) { NativeRippleLocals(nativeConfiguration, nativeIndication) }
    val pointerTracker = remember { GlassHighlightPointerTracker() }
    val policy = settings.interactionPolicy()
    val rippleActive = settings.enabled && policy.rippleIntensity > 0f
    val glassIndication = remember(pointerTracker, policy) { GlassHighlightIndication(pointerTracker, policy = policy) }
    val configuration = remember(policy) {
        RippleConfiguration(color = Color.White, rippleAlpha = RippleAlpha(
            draggedAlpha = .12f * policy.rippleIntensity, focusedAlpha = .12f * policy.rippleIntensity,
            hoveredAlpha = .08f * policy.rippleIntensity, pressedAlpha = .12f * policy.rippleIntensity,
        ))
    }
    CompositionLocalProvider(
        LocalNativeRippleLocals provides native,
        LocalGlassRippleActive provides rippleActive,
        LocalRippleConfiguration provides if (rippleActive) configuration else native.configuration,
        LocalIndication provides if (rippleActive) glassIndication else native.indication,
    ) {
        Box(
            modifier = Modifier.trackGlassHighlightPointers(pointerTracker),
            propagateMinConstraints = true,
        ) { content() }
    }
}
