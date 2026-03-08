package com.metrolist.shared.ui.navigation

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material3.MotionScheme
import androidx.compose.ui.graphics.TransformOrigin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationMotionTest {
    @Test
    fun pagesScaleAndFadeTogetherWithTheFastThemeSpecs() {
        for (scheme in listOf(MotionScheme.expressive(), MotionScheme.standard())) {
            val motion = NavigationMotion(scheme)
            val transform = motion.transition()
            assertEquals(
                scaleIn(scheme.fastSpatialSpec(), initialScale = 0.96f) + fadeIn(scheme.fastEffectsSpec()),
                transform.targetContentEnter,
            )
            assertEquals(
                scaleOut(scheme.fastSpatialSpec(), targetScale = 0.96f) + fadeOut(scheme.fastEffectsSpec()),
                transform.initialContentExit,
            )
            assertEquals(transform.targetContentEnter, motion.transition(origin = TransformOrigin(0.1f, 0.8f)).targetContentEnter)
            assertEquals(transform.targetContentEnter, motion.predictiveBackTransition().targetContentEnter)
            assertEquals(transform.initialContentExit, motion.predictiveBackTransition().initialContentExit)
        }
    }

    @Test
    fun disabledAnimationsRemovePageAndPopupTransforms() {
        val motion = NavigationMotion(MotionScheme.expressive(), enabled = false)
        assertEquals(EnterTransition.None, motion.transition().targetContentEnter)
        assertEquals(ExitTransition.None, motion.transition().initialContentExit)
        assertEquals(EnterTransition.None, motion.predictiveBackTransition().targetContentEnter)
        assertEquals(ExitTransition.None, motion.predictiveBackTransition().initialContentExit)
        assertEquals(EnterTransition.None, motion.popupEnter)
        assertEquals(ExitTransition.None, motion.popupExit)
    }

    @Test
    fun pageAndPredictiveBackFadesHaveNoBlackInterval() {
        for (scheme in listOf(MotionScheme.expressive(), MotionScheme.standard())) {
            val motion = NavigationMotion(scheme)
            val outgoing = TargetBasedAnimation(motion.effectsSpec, Float.VectorConverter, 1f, 0f)
            val incoming = TargetBasedAnimation(motion.effectsSpec, Float.VectorConverter, 0f, 1f)
            val durationMillis = maxOf(outgoing.durationNanos, incoming.durationNanos) / 1_000_000 + 1
            var sawOverlap = false

            for (millis in (0..durationMillis) + (durationMillis downTo 0)) {
                val nanos = millis * 1_000_000
                val outgoingAlpha = outgoing.getValueFromNanos(nanos).coerceIn(0f, 1f)
                val incomingAlpha = incoming.getValueFromNanos(nanos).coerceIn(0f, 1f)
                assertTrue(maxOf(outgoingAlpha, incomingAlpha) >= 0.5f, "Both pages are dim at ${millis}ms")
                assertEquals(1f, outgoingAlpha + incomingAlpha, absoluteTolerance = 0.001f)
                sawOverlap = sawOverlap || (outgoingAlpha > 0f && incomingAlpha > 0f)
            }
            assertTrue(sawOverlap)
        }
    }

    @Test
    fun standaloneInputWaitsForEnterAndExitToSettle() {
        val visible = EnterExitState.Visible
        assertFalse(transitionAcceptsInput(EnterExitState.PreEnter, visible, visible, running = false))
        assertFalse(transitionAcceptsInput(EnterExitState.PreEnter, visible, visible, running = true))
        assertFalse(transitionAcceptsInput(visible, visible, visible, running = true))
        assertTrue(transitionAcceptsInput(visible, visible, visible, running = false))
        assertFalse(transitionAcceptsInput(visible, EnterExitState.PostExit, visible, running = false))
        assertFalse(transitionAcceptsInput(EnterExitState.PostExit, EnterExitState.PostExit, visible, running = false))
        assertFalse(transitionAcceptsInput(EnterExitState.PostExit, visible, visible, running = true))
        assertTrue(transitionAcceptsInput(visible, visible, visible, running = false))
    }

    @Test
    fun popupInputWaitsForOpeningClosingAndReopeningToSettle() {
        assertFalse(transitionAcceptsInput(false, true, true, running = false))
        assertFalse(transitionAcceptsInput(false, true, true, running = true))
        assertFalse(transitionAcceptsInput(true, true, true, running = true))
        assertTrue(transitionAcceptsInput(true, true, true, running = false))
        assertFalse(transitionAcceptsInput(true, false, true, running = false))
        assertFalse(transitionAcceptsInput(false, false, true, running = false))
        assertFalse(transitionAcceptsInput(false, true, true, running = true))
    }

    @Test
    fun cancelledPredictiveBackReleasesInputEvenWithAStaleChild() {
        val visible = EnterExitState.Visible
        assertTrue(transitionAcceptsInput(visible, visible, visible, running = false, parentSettled = true))
        assertFalse(transitionAcceptsInput(visible, visible, visible, running = false, parentSettled = false))
        assertFalse(transitionAcceptsInput(visible, EnterExitState.PostExit, visible, running = true, parentSettled = false))
        assertTrue(transitionAcceptsInput(EnterExitState.PostExit, visible, visible, running = true, parentSettled = true))
        assertTrue(transitionAcceptsInput(visible, visible, visible, running = false, parentSettled = true))
    }

    @Test
    fun completedOrInterruptedBackReleasesOnlyTheSettledDestination() {
        val visible = EnterExitState.Visible
        assertFalse(transitionAcceptsInput(EnterExitState.PreEnter, visible, visible, running = false, parentSettled = false))
        assertFalse(transitionAcceptsInput(visible, visible, visible, running = true, parentSettled = false))
        assertTrue(transitionAcceptsInput(EnterExitState.PreEnter, visible, visible, running = true, parentSettled = true))
        assertFalse(transitionAcceptsInput(visible, EnterExitState.PostExit, visible, running = true, parentSettled = true))
        assertFalse(
            transitionAcceptsInput(EnterExitState.PostExit, EnterExitState.PostExit, visible, running = false, parentSettled = true),
        )
        assertFalse(transitionAcceptsInput(visible, visible, visible, running = true, parentSettled = false))
        assertTrue(transitionAcceptsInput(visible, visible, visible, running = true, parentSettled = true))
    }

    @Test
    fun cancelledBackWaitsForReturnAndDoesNotExemptTheNextGesture() {
        val input = NavigationInputState()
        assertTrue(input.acceptsInput(transitionSettled = true))
        input.beginBack()
        assertFalse(input.acceptsInput(transitionSettled = true))
        assertFalse(input.acceptsInput(transitionSettled = false))
        input.cancelBack()
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
        input.beginBack()
        assertFalse(input.acceptsInput(transitionSettled = true))
        input.reset()
        assertFalse(input.acceptsInput(transitionSettled = false))
        assertTrue(input.acceptsInput(transitionSettled = true))
    }

    @Test
    fun expressivePagesReverseTheSameSourceScaleForPopAndScrub() {
        val origin = TransformOrigin(0.2f, 0.7f)
        val motion = NavigationMotion(MotionScheme.standard())
        val spatial = MotionScheme.expressive().defaultSpatialSpec<Float>()
        val push = motion.transition(NavigationPattern.Expressive, origin = origin)
        val pop = motion.transition(NavigationPattern.Expressive, isPop = true, origin = origin)
        assertEquals(scaleIn(spatial, initialScale = 0.8f, transformOrigin = origin) + fadeIn(motion.effectsSpec), push.targetContentEnter)
        assertEquals(scaleOut(spatial, targetScale = 0.8f, transformOrigin = origin) + fadeOut(motion.effectsSpec), pop.initialContentExit)
        val scrub = motion.predictiveBackTransition(NavigationPattern.Expressive, origin)
        assertEquals(pop.targetContentEnter, scrub.targetContentEnter)
        assertEquals(pop.initialContentExit, scrub.initialContentExit)
        val disabled = NavigationMotion(MotionScheme.expressive(), enabled = false)
        assertEquals(EnterTransition.None, disabled.transition(NavigationPattern.Expressive).targetContentEnter)
        assertEquals(ExitTransition.None, disabled.predictiveBackTransition(NavigationPattern.Expressive).initialContentExit)
    }
}
