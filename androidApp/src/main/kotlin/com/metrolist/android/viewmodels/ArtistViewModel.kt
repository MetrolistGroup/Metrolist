package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.api.artwork.AnimatedArtworkService
import kotlinx.coroutines.CoroutineScope
import com.metrolist.shared.viewmodels.ArtistViewModel as SharedArtistViewModel

class ArtistViewModel(
    artistId: String? = null,
    animatedArtworkService: AnimatedArtworkService,
    applicationScope: CoroutineScope,
) : ViewModel() {
    val shared = SharedArtistViewModel(artistId, viewModelScope, animatedArtworkService, applicationScope)
}
