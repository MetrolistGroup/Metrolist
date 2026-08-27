package com.metrolist.music.features.comments.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.metrolist.music.R
import com.metrolist.music.features.comments.TimedCommentsRepository
import com.metrolist.innertube.models.TimedComment
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val COMMENT_DISPLAY_INTERVAL_MS = 5_000L
private const val LOAD_TRIGGER_INDEX = 4
private const val LOAD_TRIGGER_STEP = 10

@Composable
fun TimedCommentsStrip(
    videoId: String?,
    positionMs: Long,
    enabled: Boolean,
    textColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
) {
    var activeComment by remember(videoId) { mutableStateOf<TimedComment?>(null) }

    LaunchedEffect(enabled, videoId) {
        activeComment = null
        if (!enabled || videoId.isNullOrBlank()) return@LaunchedEffect

        var loaded = TimedCommentsRepository.loadNextWindow(videoId, reset = true)
        var comments = loaded.comments
        var hasMore = loaded.hasMore
        var displayIndex = 0
        var requestedThreshold = -1
        var nextWindowJob: Job? = null

        while (isActive) {
            if (comments.isEmpty()) {
                if (!hasMore) break
                delay(1_000L)
                loaded = TimedCommentsRepository.loadNextWindow(videoId)
                comments = loaded.comments
                hasMore = loaded.hasMore
                continue
            }

            if (displayIndex >= comments.size) displayIndex = 0
            activeComment = comments[displayIndex]

            val shouldLoadNextWindow =
                displayIndex >= LOAD_TRIGGER_INDEX &&
                    (displayIndex - LOAD_TRIGGER_INDEX) % LOAD_TRIGGER_STEP == 0 &&
                    requestedThreshold != displayIndex &&
                    hasMore
            if (shouldLoadNextWindow && nextWindowJob?.isActive != true) {
                val threshold = displayIndex
                val previousCount = comments.size
                nextWindowJob = launch {
                    val next = TimedCommentsRepository.loadNextWindow(videoId)
                    if (isActive) {
                        loaded = next
                        comments = next.comments
                        hasMore = next.hasMore
                        if (next.comments.size > previousCount || !next.hasMore) {
                            requestedThreshold = threshold
                        }
                    }
                }
            }

            delay(COMMENT_DISPLAY_INTERVAL_MS)
            displayIndex =
                if (displayIndex + 1 < comments.size) {
                    displayIndex + 1
                } else {
                    0
                }
        }
    }

    if (!enabled) return

    AnimatedVisibility(
        visible = activeComment != null,
        enter = expandVertically(expandFrom = Alignment.Top) + slideInVertically(initialOffsetY = { -it / 4 }) + fadeIn(),
        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(50.dp)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .offset(x = 3.dp, y = (-3).dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CommentAvatar(
            avatarUrl = activeComment?.avatarUrl,
            modifier = Modifier.size(34.dp),
        )

        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.CenterStart,
        ) {
            AnimatedContent(
                targetState = activeComment,
                transitionSpec = {
                    (slideInHorizontally { width -> width / 3 } + fadeIn()) togetherWith
                        (slideOutHorizontally { width -> -width / 3 } + fadeOut())
                },
                label = "featuredCommentTransition",
            ) { comment ->
                comment?.let {
                    Text(
                        text = it.text,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Clip,
                        modifier = Modifier.basicMarquee(
                            iterations = Int.MAX_VALUE,
                            initialDelayMillis = 900,
                            velocity = 35.dp,
                        ),
                    )
                }
            }
        }
        }
    }
}

@Composable
private fun CommentAvatar(
    avatarUrl: String?,
    modifier: Modifier = Modifier,
) {
    if (avatarUrl.isNullOrBlank()) {
        Icon(
            painter = painterResource(R.drawable.person),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .clip(CircleShape)
                .padding(4.dp),
        )
    } else {
        AsyncImage(
            model = avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.clip(CircleShape),
        )
    }
}
