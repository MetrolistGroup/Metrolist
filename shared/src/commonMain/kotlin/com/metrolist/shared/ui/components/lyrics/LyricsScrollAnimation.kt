/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Some UI stuff inside of this file are using YouLyPlus code under MIT code
 */

package com.metrolist.shared.ui.components.lyrics

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import com.metrolist.shared.ui.components.lyrics.words.FixedMotionDurationScale
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.TimeSource

internal val LyPlusScrollEasing = CubicBezierEasing(0.41f, 0f, 0.12f, 0.99f)

internal const val LYRICS_SCROLL_LOCK_MS = 400L
internal const val LYRICS_FORCED_SCROLL_MS = 500
internal const val LYRICS_FAR_JUMP_VIEWPORTS = 2f

private const val MAX_STAGGER_STEPS = 12
private const val SUPPRESS_TAIL_MS = 250L
private const val DEFAULT_FLIP_DURATION_MS = 400L
private const val MIN_FLIP_DISTANCE_PX = 0.5f
private const val SHIFT_RING = 32

private val springCache = LinkedHashMap<Long, LyPlusSpring>(64)
private val springCacheLock = SynchronizedObject()

internal fun lyPlusSpringFor(
    durationMs: Long,
    zeta: Float = SPRING_ZETA_DEFAULT,
): LyPlusSpring {
    val durKey = ((durationMs.coerceAtLeast(1L) + 5L) / 10L * 10L).toInt().coerceAtLeast(10)
    val clampedZeta = (kotlin.math.round(zeta.coerceIn(0.3f, 0.97f) * 50f) / 50f)
    val zetaKey = (clampedZeta * 100f).roundToInt()
    val cacheKey = (durKey.toLong() shl 16) or (zetaKey.toLong() and 0xFFFFL)
    synchronized(springCacheLock) {
        springCache.remove(cacheKey)?.let {
            springCache[cacheKey] = it
            return it
        }
        if (springCache.size >= 64) {
            val eldest = springCache.entries.iterator()
            if (eldest.hasNext()) {
                eldest.next()
                eldest.remove()
            }
        }
        return LyPlusSpring(durKey.toFloat(), clampedZeta).also { springCache[cacheKey] = it }
    }
}

internal class LyPlusSpring(
    private val peakMs: Float,
    zeta: Float = SPRING_ZETA_DEFAULT,
    settleRatio: Float = 1.5f,
) {
    private val tailMs = peakMs * settleRatio
    private val peakValue: Float
    val totalMs: Float = peakMs + tailMs

    init {
        val peakS = peakMs / 1000.0
        val omegaD = PI / peakS
        val omega0 = omegaD / sqrt(1.0 - zeta * zeta)
        val decayRate = zeta * omega0
        val dt = 1.0 / 120.0
        var pv = 1.0
        var t = 0.0
        while (t <= peakS) {
            pv = 1.0 - exp(-decayRate * t) * (cos(omegaD * t) + (decayRate / omegaD) * sin(omegaD * t))
            t += dt
        }
        peakValue = pv.toFloat()
    }

    fun value(tMs: Float): Float =
        when {
            tMs <= 0f -> {
                0f
            }

            tMs >= totalMs -> {
                1f
            }

            tMs < peakMs -> {
                peakValue * LyPlusScrollEasing.transform(tMs / peakMs)
            }

            else -> {
                val u = (tMs - peakMs) / tailMs
                1f + (peakValue - 1f) * 0.5f * (1f + cos(PI.toFloat() * u))
            }
        }
}

internal class LyricsScrollStagger(
    private val scrollingDown: Boolean,
    private val referenceIndex: Int,
    val durationMs: Long,
    val delayIncrementMs: Float,
    private val linesBefore: IntArray,
    private val staggerAfterIndex: Int = referenceIndex,
    val zeta: Float = SPRING_ZETA_DEFAULT,
    private val maxSteps: Int = MAX_STAGGER_STEPS,
) {
    fun steps(index: Int): Int {
        val last = linesBefore.size - 1
        if (last < 0) return 0
        val steps =
            if (scrollingDown) {
                if (index <= staggerAfterIndex) {
                    0
                } else {
                    linesBefore[(index + 1).coerceAtMost(last)] -
                        linesBefore[(staggerAfterIndex + 1).coerceIn(0, last)]
                }
            } else {
                if (index >= referenceIndex) {
                    0
                } else {
                    linesBefore[(referenceIndex + 1).coerceAtMost(last)] -
                        linesBefore[(index + 1).coerceAtMost(last)]
                }
            }
        return steps.coerceIn(0, maxSteps)
    }
}

internal class LyricsItemFlip {
    var lastTop = Float.NaN
    var lastIndex = 0
    var stagedOffset by mutableFloatStateOf(0f)
    var seenShiftSeq = 0L
    var seenResetSeq = 0L
    private var layers by mutableStateOf<List<FlipLayer>>(emptyList())

    private class FlipLayer(
        val offset: Float,
        val progress: Animatable<Float, AnimationVector1D>,
    ) {
        var job: Job? = null

        fun translation(): Float = offset * (1f - progress.value)
    }

    fun translation(): Float {
        val current = layers
        var sum = stagedOffset
        for (i in current.indices) sum += current[i].translation()
        return sum
    }

    fun clearLayers() {
        stagedOffset = 0f
        val current = layers
        if (current.isNotEmpty()) {
            layers = emptyList()
            for (i in current.indices) current[i].job?.cancel()
        }
    }

    fun reset(top: Float) {
        lastTop = top
        clearLayers()
    }

    fun add(
        scope: CoroutineScope,
        offset: Float,
        delayMs: Float,
        spring: LyPlusSpring,
        elapsedMs: Float = 0f,
    ) {
        if (abs(offset) < MIN_FLIP_DISTANCE_PX) return
        val wait = (delayMs - elapsedMs).coerceAtLeast(0f)
        val duration = spring.totalMs.roundToInt().coerceAtLeast(1)
        val easing = Easing { f -> spring.value(f * spring.totalMs) }
        val layer = FlipLayer(offset, Animatable(0f))
        layers = layers + layer
        layer.job =
            scope.launch {
                try {
                    withContext(FixedMotionDurationScale) {
                        layer.progress.animateTo(
                            1f,
                            tween(duration, wait.roundToInt(), easing),
                        )
                    }
                } finally {
                    layers = layers - layer
                }
            }
    }
}

internal fun buildLinesBefore(processedItems: List<LyricsListItem>): IntArray {
    val out = IntArray(processedItems.size + 1)
    for (i in processedItems.indices) {
        out[i + 1] = out[i] + if (processedItems[i] is LyricsListItem.Line) 1 else 0
    }
    return out
}

@Stable
internal class LyricsScrollShifter {
    private val origin = TimeSource.Monotonic.markNow()
    private var scope: CoroutineScope? = null
    private var suppressedUntilMs = 0f
    private var stagger: LyricsScrollStagger? = null
    private var txStartMs = 0f
    private var txEndMs = 0f
    private var lastDurationMs = DEFAULT_FLIP_DURATION_MS
    private var lastZeta = SPRING_ZETA_DEFAULT
    private var resetSeq by mutableLongStateOf(0L)
    private val stagedFlips = LinkedHashSet<LyricsItemFlip>()
    private val measuredBgHeights = HashMap<Int, Int>()
    private val reportedBgHeights = HashMap<Int, Int>()

    var isStaging: Boolean = false
        private set

    var isReflowing: Boolean = false

    val hasStagedOffsets: Boolean get() = stagedFlips.isNotEmpty()
    val scrollDurationMs: Long get() = lastDurationMs
    val scrollZeta: Float get() = lastZeta
    val isSuppressed: Boolean get() = nowMs() < suppressedUntilMs
    val isRunning: Boolean get() = stagger != null && nowMs() < txEndMs

    var reflowSignal by mutableLongStateOf(0L)
        private set

    private val shiftIndex = IntArray(SHIFT_RING)
    private val shiftDelta = FloatArray(SHIFT_RING)
    private val shiftSeqs = LongArray(SHIFT_RING)
    private var shiftSeq = 0L

    private fun nowMs(): Float = origin.elapsedNow().inWholeNanoseconds / 1_000_000f

    fun updateBgHeight(
        index: Int,
        measuredHeight: Int,
        reportedHeight: Int,
    ) {
        if (measuredHeight > 0) {
            measuredBgHeights[index] = measuredHeight
        }
        reportedBgHeights[index] = reportedHeight
    }

    fun pendingBgDelta(
        index: Int,
        shouldBeVisible: Boolean,
    ): Int {
        val measured = measuredBgHeights[index] ?: return 0
        val currentReported = reportedBgHeights[index] ?: if (shouldBeVisible) measured else 0
        val targetReported = if (shouldBeVisible) measured else 0
        return targetReported - currentReported
    }

    fun delayMsFor(index: Int): Int {
        val now = nowMs()
        if (isReflowing || now < suppressedUntilMs) return 0
        val running = stagger?.takeIf { now < txEndMs } ?: return 0
        val elapsedMs = (now - txStartMs).coerceAtLeast(0f)
        val delayMs = running.steps(index) * running.delayIncrementMs
        return (delayMs - elapsedMs).coerceAtLeast(0f).roundToInt()
    }

    fun translationFor(flip: LyricsItemFlip): Float {
        val currentReset = resetSeq
        if (flip.seenResetSeq != currentReset || nowMs() < suppressedUntilMs) {
            flip.seenResetSeq = currentReset
            stagedFlips.remove(flip)
            flip.clearLayers()
            return 0f
        }
        return flip.translation()
    }

    fun beginStaging() {
        if (nowMs() < suppressedUntilMs) return
        isStaging = true
    }

    private fun clearStaged() {
        isStaging = false
        if (stagedFlips.isNotEmpty()) {
            for (flip in stagedFlips) {
                flip.stagedOffset = 0f
            }
            stagedFlips.clear()
        }
    }

    fun cancelStaging() {
        commitStaged()
    }

    fun commitStaged() {
        isStaging = false
        if (stagedFlips.isEmpty()) return
        val sc = scope
        val now = nowMs()
        if (sc == null || now < suppressedUntilMs) {
            clearStaged()
            return
        }
        val running = stagger?.takeIf { now < txEndMs }
        val elapsedMs = if (running != null) (now - txStartMs).coerceAtLeast(0f) else 0f
        val duration = running?.durationMs ?: lastDurationMs
        val zeta = running?.zeta ?: lastZeta
        val spring = lyPlusSpringFor(duration, zeta)
        val toCommit = stagedFlips.toList()
        stagedFlips.clear()
        for (flip in toCommit) {
            val offset = flip.stagedOffset
            flip.stagedOffset = 0f
            if (abs(offset) >= MIN_FLIP_DISTANCE_PX) {
                val delayMs =
                    if (!isReflowing && running != null) {
                        running.steps(flip.lastIndex) * running.delayIncrementMs
                    } else {
                        0f
                    }
                flip.add(
                    scope = sc,
                    offset = offset,
                    delayMs = delayMs,
                    spring = spring,
                    elapsedMs = elapsedMs,
                )
            }
        }
    }

    fun arm(stagger: LyricsScrollStagger) {
        isStaging = false
        lastDurationMs = stagger.durationMs
        lastZeta = stagger.zeta
        val now = nowMs()
        if (now < suppressedUntilMs) {
            clearStaged()
            return
        }
        this.stagger = stagger
        txStartMs = now
        txEndMs = now + MAX_STAGGER_STEPS * stagger.delayIncrementMs + lyPlusSpringFor(stagger.durationMs, stagger.zeta).totalMs
    }

    fun recordLayoutShift(
        index: Int,
        delta: Float,
    ) {
        if (abs(delta) < MIN_FLIP_DISTANCE_PX) return
        val seq = ++shiftSeq
        val slot = (seq % SHIFT_RING).toInt()
        shiftIndex[slot] = index
        shiftDelta[slot] = delta
        shiftSeqs[slot] = seq
        reflowSignal = seq
    }

    private fun consumeLayoutShift(
        flip: LyricsItemFlip,
        index: Int,
    ): Float {
        var sum = 0f
        for (i in 0 until SHIFT_RING) {
            if (shiftSeqs[i] > flip.seenShiftSeq && shiftIndex[i] < index) sum += shiftDelta[i]
        }
        flip.seenShiftSeq = shiftSeq
        return sum
    }

    fun onPlacedWhileScrolling(
        flip: LyricsItemFlip,
        index: Int,
        top: Float,
    ) {
        val wasUnplaced = flip.lastTop.isNaN()
        flip.lastTop = top
        flip.lastIndex = index
        flip.seenResetSeq = resetSeq
        if (flip.stagedOffset != 0f) {
            flip.stagedOffset = 0f
            stagedFlips.remove(flip)
        }
        if (wasUnplaced || nowMs() < suppressedUntilMs) {
            flip.seenShiftSeq = shiftSeq
            return
        }
        val shift = consumeLayoutShift(flip, index)
        if (abs(shift) < MIN_FLIP_DISTANCE_PX) return
        scope?.let { flip.add(it, -shift, 0f, lyPlusSpringFor(lastDurationMs, lastZeta)) }
    }

    fun suppress(forMs: Long) {
        clearStaged()
        val tail = if (forMs > 0L) SUPPRESS_TAIL_MS else 0L
        suppressedUntilMs = maxOf(suppressedUntilMs, nowMs() + forMs + tail)
        stagger = null
        resetSeq++
    }

    fun clearSuppression() {
        suppressedUntilMs = 0f
    }

    fun onPlaced(
        flip: LyricsItemFlip,
        index: Int,
        top: Float,
        enabled: Boolean,
    ) {
        val now = nowMs()
        flip.seenResetSeq = resetSeq
        flip.seenShiftSeq = shiftSeq
        flip.lastIndex = index
        if (!enabled || now < suppressedUntilMs) {
            stagedFlips.remove(flip)
            flip.reset(top)
            return
        }
        val last = flip.lastTop
        flip.lastTop = top
        val sc = scope ?: return

        if (last.isNaN()) {
            return
        }
        val delta = last - top
        if (isStaging) {
            if (abs(delta) >= MIN_FLIP_DISTANCE_PX) {
                flip.stagedOffset += delta
                stagedFlips.add(flip)
            }
            return
        }
        val totalDelta = flip.stagedOffset + delta
        flip.stagedOffset = 0f
        stagedFlips.remove(flip)
        if (abs(totalDelta) < MIN_FLIP_DISTANCE_PX) return

        val running = stagger?.takeIf { now < txEndMs }
        val delayMs =
            if (!isReflowing && running != null) {
                running.steps(index) * running.delayIncrementMs
            } else {
                0f
            }
        val elapsedMs = if (running != null) (now - txStartMs).coerceAtLeast(0f) else 0f
        val duration = running?.durationMs ?: lastDurationMs
        val zeta = running?.zeta ?: lastZeta
        flip.add(
            scope = sc,
            offset = totalDelta,
            delayMs = delayMs,
            spring = lyPlusSpringFor(duration, zeta),
            elapsedMs = elapsedMs,
        )
    }

    suspend fun runClock() {
        coroutineScope {
            scope = this
            try {
                awaitCancellation()
            } finally {
                scope = null
            }
        }
    }
}

@Composable
internal fun Modifier.lyricsInnerFlip(
    shifter: LyricsScrollShifter?,
    index: Int,
    enabled: Boolean,
): Modifier {
    if (shifter == null) return this
    val flip = remember(shifter, index) { LyricsItemFlip() }
    return this
        .onPlaced { shifter.onPlaced(flip, index, it.positionInParent().y, enabled) }
        .graphicsLayer { translationY = if (enabled) shifter.translationFor(flip) else 0f }
}
