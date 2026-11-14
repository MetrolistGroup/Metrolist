package com.metrolist.shared.repository

import com.metrolist.shared.api.lyrics.LyricsUtils
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.LyricsProvider
import com.metrolist.shared.model.LyricsProviderResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LyricsProviderGroup(
    val provider: LyricsProvider,
    val expanded: Boolean = false,
    val loading: Boolean = false,
    val results: List<LyricsProviderResult>? = null,
    val failed: Boolean = false,
) {
    val hasResults: Boolean get() = !loading && !results.isNullOrEmpty()
}

class LyricsProviderSearch(
    private val scope: CoroutineScope,
    providerOrder: String,
    private val fetch: suspend (LyricsProvider) -> List<LyricsProviderResult>,
) {
    private var cancelled = false
    private val jobs = mutableMapOf<LyricsProvider, Job>()
    private val _groups =
        MutableStateFlow(
            (
                listOf(LyricsProvider.USER) + LyricsProvider.parseUserOrderFromStoredCsv(providerOrder) +
                    LyricsProvider.afterUserLyricsFallbacks
            ).map { LyricsProviderGroup(it, loading = true) },
        )
    val groups = _groups.asStateFlow()

    init {
        _groups.value.forEach { group ->
            val provider = group.provider
            jobs[provider] =
                scope.launch {
                    try {
                        val results = fetch(provider)
                        currentCoroutineContext().ensureActive()
                        update(provider) { it.copy(loading = false, results = results) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        update(provider) { it.copy(loading = false, failed = true) }
                    }
                }
        }
    }

    fun toggle(provider: LyricsProvider) {
        if (cancelled) return
        update(provider) { if (it.hasResults) it.copy(expanded = !it.expanded) else it }
    }

    fun select(
        result: LyricsProviderResult,
        onSelect: (LyricsProviderResult) -> Unit,
    ) {
        if (cancelled || _groups.value.none { result in it.results.orEmpty() }) return
        cancel()
        onSelect(result)
    }

    fun cancel() {
        cancelled = true
        jobs.values.forEach(Job::cancel)
    }

    private fun update(
        provider: LyricsProvider,
        transform: (LyricsProviderGroup) -> LyricsProviderGroup,
    ) {
        _groups.update { groups -> groups.map { if (it.provider == provider) transform(it) else it } }
    }
}

internal fun lyricsResultPreview(lines: List<LyricsEntry>): String {
    val synced = LyricsUtils.hasPlaybackTiming(lines)
    return lines.joinToString("\n") { line ->
        if (!synced) {
            line.text
        } else {
            val time = line.time.coerceAtLeast(0)
            val minutes = (time / 60_000).toString().padStart(2, '0')
            val seconds = (time / 1_000 % 60).toString().padStart(2, '0')
            val hundredths = (time % 1_000 / 10).toString().padStart(2, '0')
            "[$minutes:$seconds.$hundredths] ${line.text}"
        }
    }
}
