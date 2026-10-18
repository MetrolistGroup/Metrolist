/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.music.R
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
enum class CalmMood(
    @StringRes val titleRes: Int,
    @StringRes val subtitleRes: Int,
    val gradientStart: Long,
    val gradientEnd: Long,
    val queries: List<String>,
) {
    DEEP_FOCUS(
        titleRes = R.string.calm_mood_deep_focus,
        subtitleRes = R.string.calm_mood_deep_focus_desc,
        gradientStart = 0xFF27324D,
        gradientEnd = 0xFF4A5D8A,
        queries = listOf("deep focus instrumental music", "focus music for work and study"),
    ),
    FLOW_STATE(
        titleRes = R.string.calm_mood_flow_state,
        subtitleRes = R.string.calm_mood_flow_state_desc,
        gradientStart = 0xFF234A40,
        gradientEnd = 0xFF5B8C7B,
        queries = listOf("low cortisol flute music", "bansuri flute meditation", "flow state music"),
    ),
    BOLLYWOOD_INSTRUMENTAL(
        titleRes = R.string.calm_mood_bollywood,
        subtitleRes = R.string.calm_mood_bollywood_desc,
        gradientStart = 0xFF6B3F31,
        gradientEnd = 0xFFB07A5A,
        queries = listOf("bollywood instrumental", "bollywood flute instrumental", "bollywood lofi instrumental"),
    ),
    DEVOTIONAL(
        titleRes = R.string.calm_mood_devotional,
        subtitleRes = R.string.calm_mood_devotional_desc,
        gradientStart = 0xFF7A4A12,
        gradientEnd = 0xFFC08A3E,
        queries = listOf("peaceful bhajan", "mantra chanting meditation", "gurbani kirtan", "krishna flute bhajan"),
    ),
    AFFIRMATIONS(
        titleRes = R.string.calm_mood_affirmations,
        subtitleRes = R.string.calm_mood_affirmations_desc,
        gradientStart = 0xFF4A3D66,
        gradientEnd = 0xFF8A76A8,
        queries = listOf("positive affirmations music", "morning affirmations meditation"),
    ),
}

@HiltViewModel
class CalmHomeViewModel @Inject constructor() : ViewModel() {
    private val _playlists = MutableStateFlow<Map<CalmMood, List<PlaylistItem>>>(emptyMap())
    val playlists: StateFlow<Map<CalmMood, List<PlaylistItem>>> = _playlists.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        load()
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        load(onDone = { _isRefreshing.value = false })
    }

    private fun load(onDone: () -> Unit = {}) {
        viewModelScope.launch(Dispatchers.IO) {
            coroutineScope {
                CalmMood.entries.map { mood ->
                    async {
                        val items = fetchPlaylists(mood)
                        if (items.isNotEmpty()) {
                            _playlists.update { it + (mood to items) }
                        }
                    }
                }.awaitAll()
            }
            onDone()
        }
    }

    private suspend fun fetchPlaylists(mood: CalmMood): List<PlaylistItem> =
        coroutineScope {
            mood.queries.map { query ->
                async {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST)
                        .onFailure(::reportException)
                        .getOrNull()
                        ?.items
                        ?.filterIsInstance<PlaylistItem>()
                        .orEmpty()
                        .take(PLAYLISTS_PER_QUERY)
                }
            }.awaitAll()
        }.let(::interleave)
            .distinctBy { it.id }
            .take(PLAYLISTS_PER_MOOD)

    // Round-robin across queries so each banner mixes every flavour of its mood.
    private fun <T> interleave(lists: List<List<T>>): List<T> {
        val maxSize = lists.maxOfOrNull { it.size } ?: 0
        return (0 until maxSize).flatMap { i -> lists.mapNotNull { it.getOrNull(i) } }
    }

    private companion object {
        const val PLAYLISTS_PER_QUERY = 4
        const val PLAYLISTS_PER_MOOD = 8
    }
}
