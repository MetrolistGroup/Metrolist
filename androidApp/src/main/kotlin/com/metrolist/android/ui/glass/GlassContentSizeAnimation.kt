/*
 * Adapted from AndroidX AnimationModifier.kt.
 * Copyright 2020 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.metrolist.android.ui.glass

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.constrain
import kotlinx.coroutines.launch

/** Retains native layout animation while allowing Glass controls to overdraw the animated parent. */
@Composable
fun Modifier.glassAwareContentSize(
    animationSpec: FiniteAnimationSpec<IntSize> = spring(stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold),
    settings: GlassSettings = rememberGlassSettings(),
): Modifier = if (settings.usesGlassMaterial(GlassArea.ActionButtons))
    unclippedGlassContentSize(animationSpec) else animateContentSize(animationSpec)

/** Shared unclipped sizing also serves passive information rails that protrude from their rows. */
internal fun Modifier.unclippedGlassContentSize(
    animationSpec: FiniteAnimationSpec<IntSize> = spring(stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = IntSize.VisibilityThreshold),
    useBoundedHeightAfterNaturalLookahead: Boolean = false,
): Modifier = then(GlassContentSizeElement(animationSpec, useBoundedHeightAfterNaturalLookahead))

private data class GlassContentSizeElement(
    val spec: FiniteAnimationSpec<IntSize>,
    val useBoundedHeightAfterNaturalLookahead: Boolean,
) : ModifierNodeElement<GlassContentSizeNode>() {
    override fun create() = GlassContentSizeNode(spec, useBoundedHeightAfterNaturalLookahead)
    override fun update(node: GlassContentSizeNode) {
        node.spec = spec
        node.useBoundedHeightAfterNaturalLookahead = useBoundedHeightAfterNaturalLookahead
    }
    override fun InspectorInfo.inspectableProperties() { name = "glassUnclippedContentSize" }
}

private class GlassContentSizeNode(
    var spec: FiniteAnimationSpec<IntSize>,
    var useBoundedHeightAfterNaturalLookahead: Boolean,
) : Modifier.Node(), LayoutModifierNode {
    private data class Animation(val value: Animatable<IntSize, AnimationVector2D>)
    private var animation by mutableStateOf<Animation?>(null)
    private var lookaheadSize: IntSize? = null
    private var lookaheadConstraints: Constraints? = null

    override fun onReset() { animation = null }
    override fun onAttach() {
        lookaheadSize = null
        lookaheadConstraints = null
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        // A natural-height probe cannot allocate the weighted gaps in the bounded player.
        val useActualHeight = !isLookingAhead && useBoundedHeightAfterNaturalLookahead &&
            constraints.hasBoundedHeight && lookaheadConstraints?.hasBoundedHeight == false
        val child = measurable.measure(
            if (isLookingAhead || useActualHeight) constraints else lookaheadConstraints ?: constraints
        )
        val measured = IntSize(child.width, child.height)
        if (useActualHeight) {
            // This height belongs to the parent's viewport budget. Do not carry
            // its surplus into the natural height when the viewport shrinks.
            animation = null
            return layout(measured.width, measured.height) { child.placeRelative(0, 0) }
        }
        val size = if (isLookingAhead) {
            lookaheadConstraints = constraints
            lookaheadSize = measured
            measured
        } else {
            val target = lookaheadSize ?: measured
            val state = animation ?: Animation(Animatable(target, IntSize.VectorConverter, IntSize(1, 1)))
                .also { animation = it }
            val interrupted = target != state.value.value && !state.value.isRunning
            if (target != state.value.targetValue || interrupted) {
                coroutineScope.launch { state.value.animateTo(target, spec) }
            }
            constraints.constrain(state.value.value)
        }
        return layout(size.width, size.height) {
            child.place(Alignment.TopStart.align(measured, size, layoutDirection))
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.minIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.minIntrinsicHeight(width)
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurable: IntrinsicMeasurable, height: Int) =
        measurable.maxIntrinsicWidth(height)
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurable: IntrinsicMeasurable, width: Int) =
        measurable.maxIntrinsicHeight(width)
}
