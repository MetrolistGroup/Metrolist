/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sydforum.musicplay.innertube.YouTube
import com.sydforum.musicplay.innertube.pages.MoodAndGenres
import com.sydforum.musicplay.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MoodAndGenresViewModel
@Inject
constructor() : ViewModel() {
    val moodAndGenres = MutableStateFlow<List<MoodAndGenres>?>(null)

    init {
        viewModelScope.launch {
            YouTube
                .moodAndGenres()
                .onSuccess {
                    moodAndGenres.value = it
                }.onFailure {
                    reportException(it)
                }
        }
    }
}
