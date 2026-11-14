package com.metrolist.shared.ui

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

val LocalIsTv: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

val LocalIsInPipMode: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }

val LocalPlayerImmersiveMode: ProvidableCompositionLocal<Boolean> = compositionLocalOf { false }
