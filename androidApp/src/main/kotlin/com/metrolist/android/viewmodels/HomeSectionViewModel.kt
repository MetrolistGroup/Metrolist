package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.utils.withArtworkCacheRevision
import com.metrolist.shared.viewmodels.BrowseViewModel

class HomeSectionViewModel(
    section: HomeSection,
) : ViewModel() {
    val shared = BrowseViewModel(section.browseId, section.params, section.title, viewModelScope, initialSection = section)
    private var artworkCacheRevision = System.nanoTime()

    fun updateSongMetadata(song: SongItem) {
        shared.updateSongMetadata(song.copy(thumbnail = song.thumbnail.withArtworkCacheRevision(++artworkCacheRevision)))
    }
}
