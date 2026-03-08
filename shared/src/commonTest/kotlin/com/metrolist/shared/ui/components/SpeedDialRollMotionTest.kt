package com.metrolist.shared.ui.components

import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.material3.MotionScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedDialRollMotionTest {
    @Test
    fun rollAndResultHighlightFinishWithinPlaybackBudget() {
        for (scheme in listOf(MotionScheme.expressive(), MotionScheme.standard())) {
            val motion = SpeedDialRollMotion(scheme, enabled = true)
            val roll = TargetBasedAnimation(motion.spatialSpec, Float.VectorConverter, 0f, 1f)
            val highlight = TargetBasedAnimation(motion.effectsSpec, Float.VectorConverter, 0f, 1f)
            val totalNanos = roll.durationNanos + highlight.durationNanos * 2

            assertTrue(totalNanos < 600_000_000, "Roll and highlight take ${totalNanos / 1_000_000} ms")
            assertTrue(highlight.durationNanos > 0)
            assertEquals(1f, highlight.getValueFromNanos(highlight.durationNanos))
        }
    }

    @Test
    fun springOvershootKeepsTheMorphAndStretchWithinBounds() {
        val motion = SpeedDialRollMotion(MotionScheme.expressive(), enabled = true)
        val roll = TargetBasedAnimation(motion.spatialSpec, Float.VectorConverter, 0f, 1f)
        var overshot = false
        var squashed = false
        var stretched = false
        for (millis in 0..roll.durationNanos / 1_000_000 + 1) {
            val frame = motion.frame(roll.getValueFromNanos(millis * 1_000_000))
            assertTrue(frame.morph in 0f..1f)
            assertTrue(frame.scaleX in 0.82f..1.18f)
            assertTrue(frame.scaleY in 0.86f..1.14f)
            overshot = overshot || frame.rotation > 360f
            squashed = squashed || (frame.scaleX > 1f && frame.scaleY < 1f)
            stretched = stretched || (frame.scaleX < 1f && frame.scaleY > 1f)
        }
        assertTrue(overshot)
        assertTrue(squashed)
        assertTrue(stretched)
        val settled = motion.frame(roll.getValueFromNanos(roll.durationNanos))
        assertEquals(360f, settled.rotation)
        assertEquals(1f, settled.scaleX, absoluteTolerance = 0.0001f)
        assertEquals(1f, settled.scaleY, absoluteTolerance = 0.0001f)
        assertEquals(0f, settled.morph, absoluteTolerance = 0.0001f)
    }

    @Test
    fun disabledMotionKeepsOnlyTheEffectsSpec() {
        val scheme = MotionScheme.expressive()
        val motion = SpeedDialRollMotion(scheme, enabled = false)
        val roll = TargetBasedAnimation(motion.spatialSpec, Float.VectorConverter, 0f, 1f)
        assertEquals(0L, roll.durationNanos)
        assertEquals(scheme.fastEffectsSpec(), motion.effectsSpec)
        for (progress in listOf(0f, 0.25f, 0.5f, 1f, 1.1f)) {
            assertEquals(DiceRollFrame(), motion.frame(progress))
        }
    }
}
