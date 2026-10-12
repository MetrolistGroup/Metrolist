/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.viewmodels
 
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sydforum.musicplay.innertube.YouTube
import com.sydforum.musicplay.innertube.models.YTItem
import com.sydforum.musicplay.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowseViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val browseId: String? = savedStateHandle.get<String>("browseId")
 
    val items = MutableStateFlow<List<YTItem>?>(emptyList())
    val title = MutableStateFlow<String?>("")
 
    init {
        viewModelScope.launch {
            browseId?.let {
                YouTube.browse(browseId, null).onSuccess { result ->
                    // Store the title
                    title.value = result.title
 
                    // Flatten the nested structure to get all YTItems
                    val allItems = result.items.flatMap { it.items }
                    items.value = allItems
                }.onFailure {
                    reportException(it)
                }
            }
        }
    }
}
