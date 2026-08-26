/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.metrolist.music.core.R

data class LinkSegment(
    val text: String,
    val url: String? = null,
)

@Composable
fun ExpandableText(
    text: String = "",
    runs: List<LinkSegment>? = null,
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 3,
) {
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var hasOverflow by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val linkColor = MaterialTheme.colorScheme.primary
    val bodyColor = MaterialTheme.colorScheme.onSurfaceVariant

    val annotatedText: AnnotatedString = remember(text, runs, linkColor) {
        if (runs.isNullOrEmpty()) {
            AnnotatedString(text)
        } else {
            buildAnnotatedString {
                runs.forEach { segment ->
                    if (segment.url != null) {
                        pushStringAnnotation(tag = "URL", annotation = segment.url)
                        withStyle(SpanStyle(color = linkColor)) {
                            append(segment.text)
                        }
                        pop()
                    } else {
                        append(segment.text)
                    }
                }
            }
        }
    }

    Column(
        modifier = modifier.animateContentSize()
    ) {
        @Suppress("DEPRECATION")
        ClickableText(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium.copy(color = bodyColor),
            maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { textLayoutResult ->
                hasOverflow = textLayoutResult.hasVisualOverflow || textLayoutResult.lineCount > collapsedMaxLines
            },
            onClick = { offset ->
                annotatedText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                    .firstOrNull()?.let { annotation ->
                        uriHandler.openUri(annotation.item)
                        return@ClickableText
                    }
                if (hasOverflow) {
                    isExpanded = !isExpanded
                }
            }
        )
        
        if (hasOverflow) {
            Text(
                text = stringResource(if (isExpanded) R.string.show_less else R.string.show_more),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clickable { isExpanded = !isExpanded }
            )
        }
    }
}
