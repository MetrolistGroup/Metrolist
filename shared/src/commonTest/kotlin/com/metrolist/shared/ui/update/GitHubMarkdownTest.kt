package com.metrolist.shared.ui.update

import kotlin.test.Test
import kotlin.test.assertEquals

class GitHubMarkdownTest {
    @Test
    fun linksContributorsWithoutChangingEmailsOrCode() {
        val notes = "Thanks @contributor. Contact test@example.com. `@inline`\n```\n@code\n```"
        assertEquals(
            "Thanks [@contributor](https://github.com/contributor). Contact test@example.com. `@inline`\n```\n@code\n```",
            linkGitHubMentions(notes),
        )
    }
}
