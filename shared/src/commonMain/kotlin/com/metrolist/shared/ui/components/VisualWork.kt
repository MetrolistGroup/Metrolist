package com.metrolist.shared.ui.components

import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private val alwaysVisible: StateFlow<Boolean> = MutableStateFlow(true)

val LocalVisualWorkActive = compositionLocalOf { alwaysVisible }
