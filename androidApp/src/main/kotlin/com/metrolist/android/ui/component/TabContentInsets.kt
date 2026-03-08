package com.metrolist.android.ui.component

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.exclude
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val TabTopBarHeight = 64.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun tabContentTopPadding(): Dp =
    TopAppBarDefaults.windowInsets
        .exclude(WindowInsets.captionBar)
        .asPaddingValues()
        .calculateTopPadding() + TabTopBarHeight
