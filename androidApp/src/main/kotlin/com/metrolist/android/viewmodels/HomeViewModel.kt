package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.viewmodels.HomeViewModel as SharedHomeViewModel

class HomeViewModel(
    database: MusicDatabase,
    likedSongsName: String,
) : ViewModel() {
    val shared = SharedHomeViewModel(database, viewModelScope, likedSongsName = likedSongsName)
}
