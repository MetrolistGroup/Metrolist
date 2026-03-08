package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import com.metrolist.shared.ui.components.images.AsyncImage

@Composable
fun LocalPlaylistArtwork(
    thumbnails: List<String>,
    modifier: Modifier = Modifier,
    fallbackUrl: String? = null,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnails.isEmpty()) {
            PlaylistCoverImage(fallbackUrl, Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                thumbnails.take(4).chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().weight(1f)) {
                        row.forEach { thumbnail ->
                            AsyncImage(
                                url = thumbnail,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
        }
    }
}
