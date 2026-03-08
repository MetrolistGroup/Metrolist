/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 *
 * Some UI stuff inside of this file are using YouLyPlus code under MIT code
 */

package com.metrolist.shared.ui.components.lyrics

import com.metrolist.shared.model.LyricsEntry
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

internal const val SCROLL_LOOK_AHEAD_MIN_MS = 350L
internal const val SCROLL_LOOK_AHEAD_MAX_MS = 500L
internal const val SCROLL_ANIMATION_MAX_DURATION_MS = 450L
internal const val SCROLL_DELAY_INCREMENT_RATIO = 0.1f
internal const val HIGHLIGHT_LOOK_AHEAD_MS = 190L

internal const val SCROLL_LEAD_GAP_MIN_MS = 200L
internal const val SCROLL_LEAD_GAP_MAX_MS = 750L
internal const val SCROLL_LEAD_MIN_MS = 150L
internal const val SCROLL_LEAD_MAX_MS = 750L
internal const val PRE_CENTER_STAGGER_LEAD_MS = 160L
internal const val SPRING_GAP_MIN_MS = 0L
internal const val SPRING_GAP_MAX_MS = 2500L
internal const val SPRING_ZETA_TIGHT_GAP = 0.85f
internal const val SPRING_ZETA_WIDE_GAP = 0.73f
internal const val SPRING_ZETA_DEFAULT = 0.78f
internal const val DEFAULT_SUGGESTED_LINE_OFFSET_MS = -(500L + PRE_CENTER_STAGGER_LEAD_MS)
internal const val DEFAULT_APPLE_SCROLL_DURATION_MS = 750L
private const val POST_LINE_HOLD_MS = 1000L
private const val LAST_LINE_HOLD_MS = 800L
private const val BACKWARD_SEEK_THRESHOLD_MS = 80L
private const val FORWARD_SEEK_THRESHOLD_MS = 1000L

internal fun calculateScrollDuration(scrollLookAheadMs: Long): Long = minOf(SCROLL_ANIMATION_MAX_DURATION_MS, scrollLookAheadMs)

internal fun calculateScrollDelayIncrement(scrollDurationMs: Long): Float = scrollDurationMs * SCROLL_DELAY_INCREMENT_RATIO

internal fun calculateAppleScrollDuration(appleScrollDurationMs: Long): Long =
    (appleScrollDurationMs * 0.65f).roundToLong().coerceIn(SCROLL_LOOK_AHEAD_MIN_MS, SCROLL_LOOK_AHEAD_MAX_MS)

internal fun resolveDynamicSpringZeta(scrollGapT: Float?): Float {
    val rawZeta =
        if (scrollGapT != null) {
            SPRING_ZETA_TIGHT_GAP + (SPRING_ZETA_WIDE_GAP - SPRING_ZETA_TIGHT_GAP) * scrollGapT.coerceIn(0f, 1f)
        } else {
            SPRING_ZETA_DEFAULT
        }
    return (kotlin.math.round(rawZeta.coerceIn(0.3f, 0.97f) * 50f) / 50f)
}

internal class LyricsTimelineItem(
    val listIndex: Int,
    val lineIndex: Int,
    val afterLineIndex: Int,
    val startTimeMs: Long,
    val endTimeMs: Long,
    val isGap: Boolean,
    val hasBg: Boolean,
    val sourceEndTimeMs: Long = endTimeMs,
    private val mainWordRangesMs: LongArray,
) {
    fun isActiveAtSourceTime(timeMs: Long): Boolean = timeMs >= startTimeMs && timeMs < sourceEndTimeMs

    fun hasActiveMainVocals(currentTimeMs: Long): Boolean {
        var i = 0
        while (i < mainWordRangesMs.size) {
            if (currentTimeMs >= mainWordRangesMs[i] && currentTimeMs < mainWordRangesMs[i + 1]) {
                return true
            }
            i += 2
        }
        return false
    }
}

internal data class LyricsSyncSnapshot(
    val playbackListIndex: Int,
    val primaryLineIndex: Int,
    val lastActiveGroupEndListIndex: Int,
    val activeLineIndices: List<Int>,
    val scrollLookAheadMs: Long,
    val scrollDurationMs: Long,
    val scrollGapT: Float?,
    val isSeek: Boolean,
)

internal fun buildLyricsTimelineItems(
    processedItems: List<LyricsListItem>,
    lyrics: List<LyricsEntry>,
): List<LyricsTimelineItem> {
    if (processedItems.isEmpty() || lyrics.isEmpty()) return emptyList()
    val out = ArrayList<LyricsTimelineItem>(processedItems.size)

    for (listIdx in processedItems.indices) {
        when (val item = processedItems[listIdx]) {
            is LyricsListItem.Indicator -> {
                out.add(
                    LyricsTimelineItem(
                        listIndex = listIdx,
                        lineIndex = -1,
                        afterLineIndex = item.afterLineIndex,
                        startTimeMs = item.gapStartMs,
                        endTimeMs = item.gapEndMs + LYRICS_GAP_END_LEAD_MS,
                        isGap = true,
                        hasBg = false,
                        mainWordRangesMs = LongArray(0),
                    ),
                )
            }

            is LyricsListItem.Line -> {
                val entry = item.entry
                val lineIdx = item.index
                val words = entry.words
                val romanWords = entry.romanizedWords
                val wordsStartMs =
                    min(
                        words?.filter { it.endTime > it.startTime }?.minOfOrNull { (it.startTime * 1000.0).roundToLong() }
                            ?: Long.MAX_VALUE,
                        romanWords?.filter { it.endTime > it.startTime }?.minOfOrNull { (it.startTime * 1000.0).roundToLong() }
                            ?: Long.MAX_VALUE,
                    )
                val startMs = if (wordsStartMs != Long.MAX_VALUE) min(entry.time, wordsStartMs) else entry.time
                val wordsEndMs =
                    max(
                        words?.maxOfOrNull { (it.endTime * 1000.0).roundToLong() } ?: Long.MIN_VALUE,
                        romanWords?.maxOfOrNull { (it.endTime * 1000.0).roundToLong() } ?: Long.MIN_VALUE,
                    )
                val explicitEndMs =
                    when {
                        entry.endTime != null && wordsEndMs != Long.MIN_VALUE -> max(entry.endTime, wordsEndMs)
                        entry.endTime != null -> entry.endTime
                        wordsEndMs != Long.MIN_VALUE -> wordsEndMs
                        else -> null
                    }
                val rawEndMs =
                    explicitEndMs ?: run {
                        val nextLineStart = lyrics.getOrNull(lineIdx + 1)?.time
                        if (nextLineStart != null && nextLineStart > startMs) {
                            if (nextLineStart - startMs >= LYRICS_GAP_MIN_MS) startMs + 4000L else nextLineStart
                        } else {
                            startMs + 4000L
                        }
                    }
                val nextIndicator = processedItems.getOrNull(listIdx + 1) as? LyricsListItem.Indicator
                val endMs =
                    if (nextIndicator != null) {
                        min(rawEndMs, (nextIndicator.gapStartMs - LYRICS_GAP_START_LAG_MS).coerceAtLeast(startMs))
                    } else {
                        rawEndMs
                    }
                val hasBg =
                    !entry.backgroundText.isNullOrBlank() ||
                        words?.any {
                            it.isBackground &&
                                it.text
                                    .replace("(", "")
                                    .replace(")", "")
                                    .isNotBlank()
                        } == true ||
                        romanWords?.any {
                            it.isBackground &&
                                it.text
                                    .replace("(", "")
                                    .replace(")", "")
                                    .isNotBlank()
                        } == true

                val mainWordsSource = words?.takeIf { it.isNotEmpty() } ?: romanWords.orEmpty()
                val mainWordsFiltered =
                    mainWordsSource.filter { !it.isBackground && !it.isLineTimed && it.endTime > it.startTime }
                val ranges = LongArray(mainWordsFiltered.size * 2)
                var wIdx = 0
                for (w in mainWordsFiltered) {
                    ranges[wIdx++] = (w.startTime * 1000.0).roundToLong()
                    ranges[wIdx++] = (w.endTime * 1000.0).roundToLong()
                }

                out.add(
                    LyricsTimelineItem(
                        listIndex = listIdx,
                        lineIndex = lineIdx,
                        afterLineIndex = lineIdx,
                        startTimeMs = startMs,
                        endTimeMs = max(startMs, endMs),
                        sourceEndTimeMs = max(startMs, rawEndMs),
                        isGap = false,
                        hasBg = hasBg,
                        mainWordRangesMs = ranges,
                    ),
                )
            }

            is LyricsListItem.Provider -> {}
        }
    }

    return out
}

internal fun findLyricsLinesActiveAtTime(
    items: List<LyricsTimelineItem>,
    currentTimeMs: Long,
): List<Int> =
    items
        .asSequence()
        .filter { !it.isGap && currentTimeMs >= it.startTimeMs && currentTimeMs < it.endTimeMs }
        .map { it.lineIndex }
        .toList()

internal fun buildLineLyricsDeadlines(
    items: List<LyricsTimelineItem>,
    lyrics: List<LyricsEntry>,
): List<Long> =
    buildSet {
        val lookAhead = -DEFAULT_SUGGESTED_LINE_OFFSET_MS
        val overlapLookAhead = max(250L, lookAhead - 250L)
        for (item in items) {
            add(item.startTimeMs)
            add(item.startTimeMs - if (item.isGap) 0L else lookAhead)
            add(item.endTimeMs - lookAhead)
            if (item.isGap) {
                add(item.startTimeMs - HIGHLIGHT_LOOK_AHEAD_MS)
                add(item.endTimeMs - LYRICS_GAP_END_LEAD_MS)
                add(item.endTimeMs - LYRICS_GAP_END_LEAD_MS - HIGHLIGHT_LOOK_AHEAD_MS + 1L)
                add(item.endTimeMs - lookAhead + 251L)
            } else {
                add(item.startTimeMs - overlapLookAhead)
                add(item.endTimeMs - overlapLookAhead)
                add(item.endTimeMs)
                add(item.sourceEndTimeMs)
                // The final overlapping group's latest end can belong to an earlier line.
                add(item.endTimeMs + LAST_LINE_HOLD_MS)
                add(item.endTimeMs + POST_LINE_HOLD_MS)
            }
        }
        if (items.any { it.hasBg }) {
            for (line in lyrics) {
                for (word in line.words.orEmpty() + line.romanizedWords.orEmpty()) {
                    add((word.startTime * 1000.0).roundToLong())
                    add((word.endTime * 1000.0).roundToLong())
                }
            }
        }
    }.sorted()

internal class LyricsSyncEngine(
    private val items: List<LyricsTimelineItem>,
    private val firstLineListIndex: Int,
    private val activeByRealTiming: Boolean = false,
) {
    private var suggestedLineOffsetMs: Long = DEFAULT_SUGGESTED_LINE_OFFSET_MS
    private var transientLineAdjustmentMs: Long = 0L
    private var appleScrollDurationMs: Long = DEFAULT_APPLE_SCROLL_DURATION_MS
    private var scrollGapT: Float? = null
    private var lastHighlightTime: Long = Long.MIN_VALUE
    private var lastSeekGeneration: Int? = null
    private var lastShouldFollow: Boolean = true
    private var lastWordByWord: Boolean = false
    private var lastActiveItemIndex: Int = 0
    private var lastActiveGroupEndItemIndex: Int = 0
    private var currentPrimaryItemIndex: Int = -1

    private val currentActiveIndices = ArrayList<Int>(4)
    private val prevActiveIndices = ArrayList<Int>(4)
    private val activeItemIndicesSet = HashSet<Int>(8)
    private val rawActiveIndices = ArrayList<Int>(4)

    private val latestSourceEndThroughItem =
        LongArray(items.size).also { ends ->
            for (index in items.indices) {
                ends[index] = maxOf(items[index].sourceEndTimeMs, ends.getOrElse(index - 1) { Long.MIN_VALUE })
            }
        }

    private var cachedSnapshot: LyricsSyncSnapshot? = null

    private fun updateSuggestedLineOffset(
        newOffsetMs: Long,
        isInitialOrReset: Boolean,
    ) {
        val oldOffsetMs = suggestedLineOffsetMs
        suggestedLineOffsetMs = newOffsetMs
        if (!isInitialOrReset && oldOffsetMs != newOffsetMs) {
            transientLineAdjustmentMs = newOffsetMs - oldOffsetMs
        } else {
            transientLineAdjustmentMs = 0L
        }
    }

    fun peekInitialTarget(
        currentTimeMs: Long,
        shouldFollowPlayback: Boolean,
        isWordByWord: Boolean,
    ): LyricsSyncSnapshot =
        LyricsSyncEngine(items, firstLineListIndex, activeByRealTiming).update(
            currentTimeMs = currentTimeMs,
            shouldFollowPlayback = shouldFollowPlayback,
            isWordByWord = isWordByWord,
            forceScroll = true,
        )

    fun update(
        currentTimeMs: Long,
        shouldFollowPlayback: Boolean,
        isWordByWord: Boolean,
        forceScroll: Boolean = false,
        seekGeneration: Int? = null,
    ): LyricsSyncSnapshot =
        if (activeByRealTiming) {
            updateByRealTiming(currentTimeMs, shouldFollowPlayback, isWordByWord, forceScroll, seekGeneration)
        } else {
            updateByLookAhead(currentTimeMs, shouldFollowPlayback, isWordByWord, forceScroll, seekGeneration)
        }

    private fun updateByLookAhead(
        currentTimeMs: Long,
        shouldFollowPlayback: Boolean,
        isWordByWord: Boolean,
        forceScroll: Boolean = false,
        seekGeneration: Int? = null,
    ): LyricsSyncSnapshot {
        if (items.isEmpty()) {
            return LyricsSyncSnapshot(
                playbackListIndex = -1,
                primaryLineIndex = -1,
                lastActiveGroupEndListIndex = 0,
                activeLineIndices = emptyList(),
                scrollLookAheadMs = -DEFAULT_SUGGESTED_LINE_OFFSET_MS,
                scrollDurationMs = calculateScrollDuration(calculateAppleScrollDuration(DEFAULT_APPLE_SCROLL_DURATION_MS)),
                scrollGapT = null,
                isSeek = false,
            )
        }

        val existing = cachedSnapshot
        if (!forceScroll &&
            existing != null &&
            currentTimeMs == lastHighlightTime &&
            seekGeneration == lastSeekGeneration &&
            shouldFollowPlayback == lastShouldFollow &&
            isWordByWord == lastWordByWord
        ) {
            return existing
        }

        val isInitial = lastHighlightTime == Long.MIN_VALUE
        val timeDelta = if (isInitial) 0L else currentTimeMs - lastHighlightTime
        val isBackwardSeek = !isInitial && (-timeDelta) > BACKWARD_SEEK_THRESHOLD_MS
        val isForwardJump = !isInitial && timeDelta > FORWARD_SEEK_THRESHOLD_MS
        val isSeek =
            !isInitial &&
                if (seekGeneration != null) {
                    lastSeekGeneration != seekGeneration
                } else {
                    isBackwardSeek || isForwardJump
                }
        lastSeekGeneration = seekGeneration
        val isReset = forceScroll || isInitial || isSeek

        lastHighlightTime = currentTimeMs
        lastShouldFollow = shouldFollowPlayback
        lastWordByWord = isWordByWord

        if (isReset) {
            suggestedLineOffsetMs = DEFAULT_SUGGESTED_LINE_OFFSET_MS
            transientLineAdjustmentMs = 0L
            appleScrollDurationMs = DEFAULT_APPLE_SCROLL_DURATION_MS
        }

        val effectiveLinePositionMs =
            if (isReset) {
                currentTimeMs
            } else {
                max(0L, currentTimeMs + transientLineAdjustmentMs)
            }
        transientLineAdjustmentMs = 0L

        val scrollLookAheadMs = -suggestedLineOffsetMs
        val predictiveTime = effectiveLinePositionMs + scrollLookAheadMs
        val activationStartDelayMs = if (isWordByWord) 150L else 250L
        val overlapLookAheadMs = max(250L, scrollLookAheadMs - activationStartDelayMs)
        val overlapTime = effectiveLinePositionMs + overlapLookAheadMs

        val hint = if (isReset) 0 else lastActiveItemIndex
        val searchIdx = getItemIndexAtTime(currentTimeMs, predictiveTime, hint)

        rawActiveIndices.clear()
        val itemsLen = items.size
        var scanStart = max(0, (if (searchIdx != -1) searchIdx else lastActiveItemIndex) - 2)
        while (scanStart > 0 && items[scanStart - 1].endTimeMs > currentTimeMs) {
            scanStart--
        }

        var maxActiveEndMs = Long.MIN_VALUE
        for (i in scanStart until itemsLen) {
            val item = items[i]
            val startCheckTime =
                if (item.isGap) {
                    currentTimeMs
                } else {
                    val isOverlappingNext = rawActiveIndices.isNotEmpty() && item.startTimeMs < maxActiveEndMs
                    if (isOverlappingNext && !activeItemIndicesSet.contains(i)) {
                        overlapTime
                    } else {
                        predictiveTime
                    }
                }
            if (item.startTimeMs > startCheckTime && !item.isGap && item.startTimeMs > predictiveTime) break
            val nextItem = if (i + 1 < itemsLen) items[i + 1] else null
            val overlapsNextLine =
                nextItem != null &&
                    !nextItem.isGap &&
                    !item.isGap &&
                    nextItem.startTimeMs < item.endTimeMs
            val endCheckTime =
                when {
                    item.isGap -> predictiveTime
                    overlapsNextLine -> overlapTime
                    nextItem != null && !nextItem.isGap && predictiveTime >= nextItem.startTimeMs -> predictiveTime
                    else -> currentTimeMs
                }
            if (startCheckTime >= item.startTimeMs && endCheckTime < item.endTimeMs) {
                rawActiveIndices.add(i)
                if (item.endTimeMs > maxActiveEndMs) {
                    maxActiveEndMs = item.endTimeMs
                }
            }
        }

        if (!isReset && rawActiveIndices.isEmpty() && currentActiveIndices.isNotEmpty()) {
            for (k in currentActiveIndices.indices) {
                val prevIdx = currentActiveIndices[k]
                val prevItem = items.getOrNull(prevIdx)
                if (prevItem != null &&
                    predictiveTime < prevItem.endTimeMs &&
                    currentTimeMs + SCROLL_LEAD_MAX_MS + PRE_CENTER_STAGGER_LEAD_MS >= prevItem.startTimeMs
                ) {
                    rawActiveIndices.add(prevIdx)
                }
            }
        }

        if (!isReset && rawActiveIndices.isNotEmpty() && currentActiveIndices.isNotEmpty()) {
            val minCurrentActive = currentActiveIndices[0]
            var writeIdx = 0
            for (i in rawActiveIndices.indices) {
                val idx = rawActiveIndices[i]
                if (!(idx > 0 && idx < minCurrentActive)) {
                    rawActiveIndices[writeIdx++] = idx
                }
            }
            while (rawActiveIndices.size > writeIdx) {
                rawActiveIndices.removeAt(rawActiveIndices.lastIndex)
            }
            if (rawActiveIndices.isEmpty()) {
                rawActiveIndices.addAll(currentActiveIndices)
            }
        }

        if (rawActiveIndices.size >= 2) {
            var anyHasBg = false
            for (i in rawActiveIndices.indices) {
                if (items[rawActiveIndices[i]].hasBg) {
                    anyHasBg = true
                    break
                }
            }
            if (anyHasBg) {
                val lastIdx = rawActiveIndices[rawActiveIndices.size - 1]
                val prevIdx = rawActiveIndices[rawActiveIndices.size - 2]
                val isConsecutive = (lastIdx - prevIdx == 1)
                val prevItem = items[prevIdx]
                val lastItem = items[lastIdx]
                val prevHasNoActiveMainWords = !prevItem.hasActiveMainVocals(currentTimeMs)
                val prevHasBg = prevItem.hasBg
                val lastHasBg = lastItem.hasBg
                rawActiveIndices.clear()
                if (isConsecutive && (prevHasNoActiveMainWords || !prevHasBg || !lastHasBg)) {
                    rawActiveIndices.add(prevIdx)
                }
                rawActiveIndices.add(lastIdx)
            }
        }

        if (rawActiveIndices.isEmpty()) {
            val firstLineStartTime = items[0].startTimeMs
            if (predictiveTime >= firstLineStartTime) {
                if (!isReset && currentActiveIndices.isNotEmpty()) {
                    val lastPrevIdx = currentActiveIndices[currentActiveIndices.size - 1]
                    val lastPrevItem = items.getOrNull(lastPrevIdx)
                    val nextAfterPrev = if (lastPrevIdx >= 0 && lastPrevIdx + 1 < itemsLen) items[lastPrevIdx + 1] else null
                    val hasNextLyricLine = nextAfterPrev != null
                    val exitedGapLine = lastPrevItem != null && lastPrevItem.isGap && predictiveTime >= lastPrevItem.endTimeMs
                    var maxPrevEndMs = lastPrevItem?.endTimeMs ?: Long.MIN_VALUE
                    for (k in currentActiveIndices.indices) {
                        val l = items.getOrNull(currentActiveIndices[k])
                        if (l != null && l.endTimeMs > maxPrevEndMs) maxPrevEndMs = l.endTimeMs
                    }
                    val exitedLastLyricLine =
                        lastPrevItem != null &&
                            !hasNextLyricLine &&
                            (predictiveTime - maxPrevEndMs) >= LAST_LINE_HOLD_MS + (-suggestedLineOffsetMs)
                    val inactiveAfter = (predictiveTime - maxPrevEndMs) >= POST_LINE_HOLD_MS + (-suggestedLineOffsetMs)

                    if (!exitedGapLine && !exitedLastLyricLine && !inactiveAfter) {
                        for (k in currentActiveIndices.indices) {
                            val idx = currentActiveIndices[k]
                            val l = items.getOrNull(idx)
                            if (l != null && !l.isGap) {
                                rawActiveIndices.add(idx)
                            }
                        }
                    }
                } else if (searchIdx in 0 until itemsLen) {
                    val candidate = items[searchIdx]
                    val hasNextLyricLine = searchIdx + 1 < itemsLen
                    val holdWindowMs =
                        (if (hasNextLyricLine) POST_LINE_HOLD_MS else LAST_LINE_HOLD_MS) + (-suggestedLineOffsetMs)
                    if (!candidate.isGap && (predictiveTime - candidate.endTimeMs) in 0L until holdWindowMs) {
                        rawActiveIndices.add(searchIdx)
                    }
                }
            }
        }

        if (rawActiveIndices.isNotEmpty()) {
            val lastActiveIdx = rawActiveIndices[rawActiveIndices.size - 1]
            val lastActiveItem = items[lastActiveIdx]
            if (isWordByWord && !lastActiveItem.isGap) {
                val nextItem = if (lastActiveIdx + 1 < itemsLen) items[lastActiveIdx + 1] else null
                var rawEnd = lastActiveItem.endTimeMs
                for (k in rawActiveIndices.indices) {
                    val endMs = items[rawActiveIndices[k]].endTimeMs
                    if (endMs > rawEnd) rawEnd = endMs
                }
                val gap =
                    if (nextItem != null && !nextItem.isGap) {
                        nextItem.startTimeMs - rawEnd
                    } else {
                        -1L
                    }
                val clampedGap = gap.coerceIn(SCROLL_LEAD_GAP_MIN_MS, SCROLL_LEAD_GAP_MAX_MS)
                val t =
                    (clampedGap - SCROLL_LEAD_GAP_MIN_MS).toFloat() /
                        (SCROLL_LEAD_GAP_MAX_MS - SCROLL_LEAD_GAP_MIN_MS).toFloat()
                val iRound = (SCROLL_LEAD_MIN_MS + t * (SCROLL_LEAD_MAX_MS - SCROLL_LEAD_MIN_MS)).roundToLong()
                updateSuggestedLineOffset(-(iRound + PRE_CENTER_STAGGER_LEAD_MS), isReset)
                appleScrollDurationMs = iRound
                val noLineFollows = nextItem == null || nextItem.isGap
                val gapRange = (SPRING_GAP_MAX_MS - SPRING_GAP_MIN_MS).toFloat()
                scrollGapT =
                    if (noLineFollows) {
                        1f
                    } else {
                        ((gap - SPRING_GAP_MIN_MS).toFloat() / gapRange).coerceIn(0f, 1f)
                    }
            } else {
                updateSuggestedLineOffset(DEFAULT_SUGGESTED_LINE_OFFSET_MS, isReset)
                appleScrollDurationMs = DEFAULT_APPLE_SCROLL_DURATION_MS
                scrollGapT = null
            }
        }

        val appleDurationMs = calculateAppleScrollDuration(appleScrollDurationMs)
        val effectiveScrollDurationMs = calculateScrollDuration(appleDurationMs)

        var isNonEmptySubsetOfCurrent = false
        if (!isReset && rawActiveIndices.isNotEmpty() && rawActiveIndices.size <= currentActiveIndices.size) {
            isNonEmptySubsetOfCurrent = true
            for (i in rawActiveIndices.indices) {
                if (!currentActiveIndices.contains(rawActiveIndices[i])) {
                    isNonEmptySubsetOfCurrent = false
                    break
                }
            }
            if (isNonEmptySubsetOfCurrent) {
                val maxRawIdx = rawActiveIndices[rawActiveIndices.size - 1]
                for (i in currentActiveIndices.indices) {
                    val curIdx = currentActiveIndices[i]
                    if (!rawActiveIndices.contains(curIdx) && maxRawIdx >= curIdx + 2) {
                        isNonEmptySubsetOfCurrent = false
                        break
                    }
                }
            }
        }

        if (!isNonEmptySubsetOfCurrent) {
            prevActiveIndices.clear()
            prevActiveIndices.addAll(currentActiveIndices)
            currentActiveIndices.clear()
            currentActiveIndices.addAll(rawActiveIndices)
        }

        activeItemIndicesSet.clear()
        for (i in currentActiveIndices.indices) {
            activeItemIndicesSet.add(currentActiveIndices[i])
        }

        val primaryItemIndex: Int
        if (currentActiveIndices.isNotEmpty()) {
            var isContiguous = true
            for (i in 1 until currentActiveIndices.size) {
                if (currentActiveIndices[i] - currentActiveIndices[i - 1] != 1) {
                    isContiguous = false
                    break
                }
            }
            var hasIntersection = false
            for (i in currentActiveIndices.indices) {
                if (prevActiveIndices.contains(currentActiveIndices[i])) {
                    hasIntersection = true
                    break
                }
            }
            val rFirst = currentActiveIndices[0]
            val rLast = currentActiveIndices[currentActiveIndices.size - 1]
            val sLast = if (prevActiveIndices.isNotEmpty()) prevActiveIndices[prevActiveIndices.size - 1] else -1

            primaryItemIndex =
                if (isReset && currentTimeMs >= items[rLast].startTimeMs) {
                    rLast
                } else if (hasIntersection && rLast > sLast && !isContiguous) {
                    rLast
                } else if (currentActiveIndices.size > 3) {
                    max(rFirst, rLast - 2)
                } else {
                    rFirst
                }

            lastActiveItemIndex = primaryItemIndex
            lastActiveGroupEndItemIndex = rLast
        } else {
            if (predictiveTime < items[0].startTimeMs) {
                lastActiveItemIndex = 0
                lastActiveGroupEndItemIndex = 0
                primaryItemIndex = 0
            } else if (isReset && searchIdx in 0 until itemsLen) {
                lastActiveItemIndex = searchIdx
                lastActiveGroupEndItemIndex = searchIdx
                primaryItemIndex = searchIdx
            } else {
                primaryItemIndex =
                    if (currentPrimaryItemIndex in 0 until itemsLen) {
                        currentPrimaryItemIndex
                    } else {
                        lastActiveItemIndex.coerceIn(0, itemsLen - 1)
                    }
            }
        }

        currentPrimaryItemIndex = primaryItemIndex

        val primaryItem = items[primaryItemIndex]
        val playbackListIndex =
            if (shouldFollowPlayback) {
                primaryItem.listIndex
            } else {
                firstLineListIndex
            }
        val primaryLineIndex =
            if (!primaryItem.isGap) {
                primaryItem.lineIndex
            } else {
                primaryItem.afterLineIndex.coerceAtLeast(0)
            }
        val groupEndItem = items[lastActiveGroupEndItemIndex.coerceIn(0, itemsLen - 1)]

        val activeLines = ArrayList<Int>(currentActiveIndices.size)
        for (i in currentActiveIndices.indices) {
            val item = items[currentActiveIndices[i]]
            if (!item.isGap && item.lineIndex >= 0) {
                activeLines.add(item.lineIndex)
            }
        }

        val snapshot =
            LyricsSyncSnapshot(
                playbackListIndex = playbackListIndex,
                primaryLineIndex = primaryLineIndex,
                lastActiveGroupEndListIndex = groupEndItem.listIndex,
                activeLineIndices = activeLines,
                scrollLookAheadMs = -suggestedLineOffsetMs,
                scrollDurationMs = effectiveScrollDurationMs,
                scrollGapT = scrollGapT,
                isSeek = isSeek,
            )
        cachedSnapshot = snapshot
        return snapshot
    }

    private fun updateByRealTiming(
        currentTimeMs: Long,
        shouldFollowPlayback: Boolean,
        isWordByWord: Boolean,
        forceScroll: Boolean = false,
        seekGeneration: Int? = null,
    ): LyricsSyncSnapshot {
        if (items.isEmpty()) {
            return LyricsSyncSnapshot(
                playbackListIndex = -1,
                primaryLineIndex = -1,
                lastActiveGroupEndListIndex = 0,
                activeLineIndices = emptyList(),
                scrollLookAheadMs = -DEFAULT_SUGGESTED_LINE_OFFSET_MS,
                scrollDurationMs = calculateScrollDuration(calculateAppleScrollDuration(DEFAULT_APPLE_SCROLL_DURATION_MS)),
                scrollGapT = null,
                isSeek = false,
            )
        }

        val existing = cachedSnapshot
        if (!forceScroll &&
            existing != null &&
            currentTimeMs == lastHighlightTime &&
            seekGeneration == lastSeekGeneration &&
            shouldFollowPlayback == lastShouldFollow &&
            isWordByWord == lastWordByWord
        ) {
            return existing
        }

        val isInitial = lastHighlightTime == Long.MIN_VALUE
        val timeDelta = if (isInitial) 0L else currentTimeMs - lastHighlightTime
        val isBackwardSeek = !isInitial && (-timeDelta) > BACKWARD_SEEK_THRESHOLD_MS
        val isForwardJump = !isInitial && timeDelta > FORWARD_SEEK_THRESHOLD_MS
        val isSeek =
            !isInitial &&
                if (seekGeneration != null) {
                    lastSeekGeneration != seekGeneration
                } else {
                    isBackwardSeek || isForwardJump
                }
        lastSeekGeneration = seekGeneration
        val isReset = forceScroll || isInitial || isSeek

        lastHighlightTime = currentTimeMs
        lastShouldFollow = shouldFollowPlayback
        lastWordByWord = isWordByWord

        if (isReset) {
            suggestedLineOffsetMs = DEFAULT_SUGGESTED_LINE_OFFSET_MS
            appleScrollDurationMs = DEFAULT_APPLE_SCROLL_DURATION_MS
        }

        val scrollLookAheadMs = -suggestedLineOffsetMs
        val predictiveTime = currentTimeMs + scrollLookAheadMs
        val searchIdx = getItemIndexAtTime(currentTimeMs, predictiveTime, startHintIndex = -1)

        if (isReset) {
            lastActiveItemIndex = if (searchIdx != -1) searchIdx else 0
            lastActiveGroupEndItemIndex = lastActiveItemIndex
            currentPrimaryItemIndex = lastActiveItemIndex
        }

        rawActiveIndices.clear()
        val itemsLen = items.size
        var scanStart = max(0, (if (searchIdx != -1) searchIdx else lastActiveItemIndex) - 2)
        while (scanStart > 0 && latestSourceEndThroughItem[scanStart - 1] > currentTimeMs) {
            scanStart--
        }
        for (i in scanStart until itemsLen) {
            val item = items[i]
            if (item.startTimeMs > currentTimeMs) break
            if (item.isActiveAtSourceTime(currentTimeMs)) rawActiveIndices.add(i)
        }

        if (rawActiveIndices.isNotEmpty()) {
            val lastActiveIdx = rawActiveIndices[rawActiveIndices.size - 1]
            val lastActiveItem = items[lastActiveIdx]
            if (isWordByWord && !lastActiveItem.isGap) {
                val nextItem = if (lastActiveIdx + 1 < itemsLen) items[lastActiveIdx + 1] else null
                var rawEnd = lastActiveItem.endTimeMs
                for (k in rawActiveIndices.indices) {
                    val endMs = items[rawActiveIndices[k]].endTimeMs
                    if (endMs > rawEnd) rawEnd = endMs
                }
                val gap =
                    if (nextItem != null && !nextItem.isGap) {
                        nextItem.startTimeMs - rawEnd
                    } else {
                        -1L
                    }
                val clampedGap = gap.coerceIn(SCROLL_LEAD_GAP_MIN_MS, SCROLL_LEAD_GAP_MAX_MS)
                val t =
                    (clampedGap - SCROLL_LEAD_GAP_MIN_MS).toFloat() /
                        (SCROLL_LEAD_GAP_MAX_MS - SCROLL_LEAD_GAP_MIN_MS).toFloat()
                val iRound = (SCROLL_LEAD_MIN_MS + t * (SCROLL_LEAD_MAX_MS - SCROLL_LEAD_MIN_MS)).roundToLong()
                suggestedLineOffsetMs = -(iRound + PRE_CENTER_STAGGER_LEAD_MS)
                appleScrollDurationMs = iRound
                val noLineFollows = nextItem == null || nextItem.isGap
                val gapRange = (SPRING_GAP_MAX_MS - SPRING_GAP_MIN_MS).toFloat()
                scrollGapT =
                    if (noLineFollows) {
                        1f
                    } else {
                        ((gap - SPRING_GAP_MIN_MS).toFloat() / gapRange).coerceIn(0f, 1f)
                    }
            } else {
                suggestedLineOffsetMs = DEFAULT_SUGGESTED_LINE_OFFSET_MS
                appleScrollDurationMs = DEFAULT_APPLE_SCROLL_DURATION_MS
                scrollGapT = null
            }
        }

        val appleDurationMs = calculateAppleScrollDuration(appleScrollDurationMs)
        val effectiveScrollDurationMs = calculateScrollDuration(appleDurationMs)

        currentActiveIndices.clear()
        currentActiveIndices.addAll(rawActiveIndices)

        val upcomingItemIndex =
            if (searchIdx in 0 until itemsLen && items[searchIdx].startTimeMs > currentTimeMs) {
                searchIdx
            } else {
                -1
            }

        val primaryItemIndex: Int
        if (currentActiveIndices.isNotEmpty()) {
            var groupStart = currentActiveIndices.lastIndex
            while (groupStart > 0 && currentActiveIndices[groupStart] == currentActiveIndices[groupStart - 1] + 1) {
                groupStart--
            }
            primaryItemIndex = currentActiveIndices[groupStart]
            lastActiveItemIndex = primaryItemIndex
            lastActiveGroupEndItemIndex =
                if (upcomingItemIndex != -1) {
                    maxOf(currentActiveIndices.last(), upcomingItemIndex)
                } else {
                    currentActiveIndices.last()
                }
        } else {
            if (predictiveTime < items[0].startTimeMs) {
                lastActiveItemIndex = 0
                lastActiveGroupEndItemIndex = 0
                primaryItemIndex = 0
            } else if (searchIdx in 0 until itemsLen) {
                lastActiveItemIndex = searchIdx
                lastActiveGroupEndItemIndex = searchIdx
                primaryItemIndex = searchIdx
            } else {
                primaryItemIndex =
                    if (currentPrimaryItemIndex in 0 until itemsLen) {
                        currentPrimaryItemIndex
                    } else {
                        lastActiveItemIndex.coerceIn(0, itemsLen - 1)
                    }
            }
        }

        currentPrimaryItemIndex = primaryItemIndex

        val primaryItem = items[primaryItemIndex]
        var scrollItemIndex = if (upcomingItemIndex != -1) upcomingItemIndex else primaryItemIndex
        if (upcomingItemIndex != -1) {
            val upcomingStart = items[upcomingItemIndex].startTimeMs
            while (scrollItemIndex > 0) {
                val previous = items[scrollItemIndex - 1]
                if (previous.isGap || !previous.isActiveAtSourceTime(upcomingStart)) break
                scrollItemIndex--
            }
        }
        val scrollItem = items[scrollItemIndex]
        val playbackListIndex =
            if (shouldFollowPlayback) {
                scrollItem.listIndex
            } else {
                firstLineListIndex
            }
        val primaryLineIndex =
            if (!primaryItem.isGap) {
                primaryItem.lineIndex
            } else {
                primaryItem.afterLineIndex.coerceAtLeast(0)
            }
        val groupEndItem = items[lastActiveGroupEndItemIndex.coerceIn(0, itemsLen - 1)]

        val activeLines = ArrayList<Int>(currentActiveIndices.size)
        for (i in currentActiveIndices.indices) {
            val item = items[currentActiveIndices[i]]
            if (!item.isGap && item.lineIndex >= 0) {
                activeLines.add(item.lineIndex)
            }
        }

        val snapshot =
            LyricsSyncSnapshot(
                playbackListIndex = playbackListIndex,
                primaryLineIndex = primaryLineIndex,
                lastActiveGroupEndListIndex = groupEndItem.listIndex,
                activeLineIndices = activeLines,
                scrollLookAheadMs = -suggestedLineOffsetMs,
                scrollDurationMs = effectiveScrollDurationMs,
                scrollGapT = scrollGapT,
                isSeek = isSeek,
            )
        cachedSnapshot = snapshot
        return snapshot
    }

    private fun getItemIndexAtTime(
        currentTimeMs: Long,
        predictiveTimeMs: Long,
        startHintIndex: Int = 0,
    ): Int {
        val len = items.size
        if (len == 0) return -1

        fun timeFor(item: LyricsTimelineItem): Long = if (item.isGap) currentTimeMs else predictiveTimeMs

        if (startHintIndex in 0 until len) {
            val hintItem = items[startHintIndex]
            val tHint = timeFor(hintItem)
            if (tHint >= hintItem.startTimeMs && tHint < hintItem.endTimeMs) {
                return startHintIndex
            }
            if (startHintIndex + 1 < len) {
                val nextItem = items[startHintIndex + 1]
                val tNext = timeFor(nextItem)
                if (tNext >= nextItem.startTimeMs && tNext < nextItem.endTimeMs) {
                    return startHintIndex + 1
                }
            }
            if (startHintIndex - 1 >= 0) {
                val prevItem = items[startHintIndex - 1]
                val tPrev = timeFor(prevItem)
                if (tPrev >= prevItem.startTimeMs && tPrev < prevItem.endTimeMs) {
                    return startHintIndex - 1
                }
            }
        }

        var low = 0
        var high = len - 1
        var result = -1

        while (low <= high) {
            val mid = (low + high) ushr 1
            val item = items[mid]
            val t = timeFor(item)
            if (t >= item.startTimeMs && t < item.endTimeMs) {
                return mid
            } else if (t < item.startTimeMs) {
                high = mid - 1
            } else {
                low = mid + 1
                result = mid
            }
        }

        return result
    }
}
