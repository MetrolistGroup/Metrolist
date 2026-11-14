/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.shared.viewmodels.SearchViewModel as SharedSearchViewModel

class SearchViewModel : ViewModel() {
    val shared = SharedSearchViewModel(viewModelScope)
    var hasRequestedInitialFocus = false
}
