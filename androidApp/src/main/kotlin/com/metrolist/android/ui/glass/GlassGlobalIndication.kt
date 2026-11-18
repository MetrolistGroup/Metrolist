package com.metrolist.android.ui.glass

import androidx.compose.foundation.Indication
import androidx.compose.foundation.LocalIndication
import androidx.compose.runtime.Composable

/** Restyles native feedback while preserving an explicit absence of indication. */
@Composable
fun glassGlobalIndication(nativeIndication: Indication?): Indication? =
    if (nativeIndication != null && LocalGlassRippleActive.current) LocalIndication.current else nativeIndication
