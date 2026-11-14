package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.viewmodels.AboutArtistViewModel as SharedAboutArtistViewModel

class AboutArtistViewModel(
    artistId: String,
    artistName: String,
) : ViewModel() {
    val shared = SharedAboutArtistViewModel(artistId, artistName, viewModelScope)
}
