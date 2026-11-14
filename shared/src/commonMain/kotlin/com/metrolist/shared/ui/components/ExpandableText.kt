package com.metrolist.shared.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.utils.toLinkSegments
import com.metrolist.shared.utils.openUrl

@Composable
fun ExpandableText(
    text: String = "",
    modifier: Modifier = Modifier,
    collapsedMaxLines: Int = 3,
    initiallyExpanded: Boolean = false,
    showMoreText: String,
    showLessText: String,
    linkColor: Color = MaterialTheme.colorScheme.primary,
    bodyColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onExpandedChange: ((Boolean) -> Unit)? = null,
    onExpansionFinished: (() -> Unit)? = null,
) {
    var isExpanded by remember(text, initiallyExpanded) { mutableStateOf(initiallyExpanded) }
    var hasOverflow by remember(text) { mutableStateOf(false) }
    val toggleExpanded: () -> Unit = {
        isExpanded = !isExpanded
        onExpandedChange?.invoke(isExpanded)
    }

    val runs = remember(text) { text.toLinkSegments() }

    val annotatedText: AnnotatedString =
        remember(text, runs, linkColor) {
            if (runs.isEmpty()) {
                AnnotatedString(text)
            } else {
                buildAnnotatedString {
                    runs.forEach { segment ->
                        if (segment.url != null) {
                            val link =
                                LinkAnnotation.Clickable(
                                    tag = segment.url,
                                    linkInteractionListener = {
                                        openUrl(segment.url)
                                    },
                                )
                            withLink(link) {
                                withStyle(SpanStyle(color = linkColor)) {
                                    append(segment.text)
                                }
                            }
                        } else {
                            append(segment.text)
                        }
                    }
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = hasOverflow, onClickLabel = if (isExpanded) showLessText else showMoreText, onClick = toggleExpanded)
                .then(modifier)
                .animateContentSize(finishedListener = { _, _ -> if (isExpanded) onExpansionFinished?.invoke() }),
    ) {
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodyMedium.copy(color = bodyColor),
            maxLines = if (isExpanded) Int.MAX_VALUE else collapsedMaxLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { layoutResult ->
                hasOverflow = layoutResult.hasVisualOverflow || layoutResult.lineCount > collapsedMaxLines
            },
        )

        if (hasOverflow) {
            Text(
                text = if (isExpanded) showLessText else showMoreText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .padding(top = 4.dp),
            )
        }
    }
}
