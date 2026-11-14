package com.metrolist.shared.ui.update

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.mikepenz.markdown.coil3.Coil3ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.rememberMarkdownState

@Composable
fun GitHubMarkdown(
    content: String,
    modifier: Modifier = Modifier,
) {
    val markdownState = rememberReleaseNotesState(content)
    GitHubMarkdown(markdownState, modifier)
}

@Composable
fun GitHubMarkdown(
    state: State,
    modifier: Modifier = Modifier,
) {
    Markdown(
        state = state,
        modifier = modifier.fillMaxWidth(),
        imageTransformer = Coil3ImageTransformerImpl,
        typography =
            markdownTypography(
                h1 = MaterialTheme.typography.headlineMedium,
                h2 = MaterialTheme.typography.headlineSmall,
                h3 = MaterialTheme.typography.titleLarge,
                h4 = MaterialTheme.typography.titleMedium,
                h5 = MaterialTheme.typography.titleSmall,
                h6 = MaterialTheme.typography.bodyLarge,
            ),
    )
}

private val GitHubMentionRegex = Regex("(?<![A-Za-z0-9_@])@([A-Za-z0-9](?:[A-Za-z0-9-]{0,38}))")

internal fun linkGitHubMentions(markdown: String): String {
    var inFence = false
    return markdown.lineSequence().joinToString("\n") { line ->
        if (line.trimStart().startsWith("```")) {
            inFence = !inFence
            line
        } else if (inFence) {
            line
        } else {
            line
                .split('`')
                .mapIndexed { index, segment ->
                    if (index % 2 == 1) {
                        segment
                    } else {
                        segment.replace(GitHubMentionRegex) { match ->
                            val user = match.groupValues[1]
                            "[@$user](https://github.com/$user)"
                        }
                    }
                }.joinToString("`")
        }
    }
}

@Composable
fun rememberReleaseNotesState(content: String): State {
    val linkedMentions = remember(content) { linkGitHubMentions(content) }
    val state by rememberMarkdownState(linkedMentions).state.collectAsState()
    return state
}
