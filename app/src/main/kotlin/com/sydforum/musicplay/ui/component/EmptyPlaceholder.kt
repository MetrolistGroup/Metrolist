/**
 * MusicPlayer Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.sydforum.musicplay.ui.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

@Composable
fun EmptyPlaceholder(
    @DrawableRes icon: Int,
    text: String,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier =
        modifier
            .fillMaxSize()
            .padding(12.dp),
    ) {
        Image(
            painter = painterResource(icon),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onBackground),
            modifier = Modifier.size(64.dp),
        )

        Spacer(Modifier.height(12.dp))

        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
