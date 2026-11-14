package com.metrolist.shared.utils

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged

suspend fun <T> Flow<T>.collectLatestWhileVisible(
    visible: Flow<Boolean>,
    action: suspend (T) -> Unit,
) {
    visible.distinctUntilChanged().collectLatest { active ->
        if (active) collectLatest(action)
    }
}
