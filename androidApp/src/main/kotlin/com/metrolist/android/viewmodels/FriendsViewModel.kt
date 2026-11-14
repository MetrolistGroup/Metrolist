package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.viewmodels.FRIENDS_REFRESH_INTERVAL_MS
import com.metrolist.shared.viewmodels.FriendsViewModel

class FriendsViewModel : ViewModel() {
    val sharedViewModel =
        FriendsViewModel(viewModelScope)
    val uiState = sharedViewModel.uiState

    fun startPolling(intervalMs: Long = FRIENDS_REFRESH_INTERVAL_MS) = sharedViewModel.startPolling(intervalMs)

    fun stopPolling() = sharedViewModel.stopPolling()

    override fun onCleared() {
        sharedViewModel.stopPolling()
    }
}
