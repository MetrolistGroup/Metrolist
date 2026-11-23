/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.sponsor_developer
import com.metrolist.shared.generated.resources.support_prompt_message
import com.metrolist.shared.generated.resources.support_prompt_never_show
import com.metrolist.shared.generated.resources.support_prompt_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.AlertDialog
import com.metrolist.shared.ui.components.PopupVisibility
import com.metrolist.shared.ui.components.images.AsyncImage
import org.jetbrains.compose.resources.stringResource
import kotlin.random.Random

private val SupportedDeveloperHandles = listOf("nyxiereal", "mostafaalagamy", "adrielGGmotion", "l6t9")
private val SupportPromptLaunchRange = 8..20

internal object SupportPromptSchedule {
    private var dueThisLaunch: Boolean? = null

    /** Counts one app launch per process and reports whether the prompt is due. */
    fun claimLaunch(settings: Settings = Settings.shared): Boolean =
        !settings.supportPromptDismissed && (dueThisLaunch ?: isDue(settings).also { dueThisLaunch = it })

    fun dismissForLaunch() {
        dueThisLaunch = false
    }

    internal fun isDue(
        settings: Settings,
        random: Random = Random.Default,
    ): Boolean {
        if (settings.supportPromptDismissed) return false
        if (settings.supportPromptLaunchThreshold !in SupportPromptLaunchRange) {
            settings.supportPromptLaunchThreshold = SupportPromptLaunchRange.random(random)
        }
        val launches = settings.supportPromptLaunches + 1
        if (launches < settings.supportPromptLaunchThreshold) {
            settings.supportPromptLaunches = launches
            return false
        }
        // Dismissing without the button waits another random stretch of launches.
        settings.supportPromptLaunches = 0
        settings.supportPromptLaunchThreshold = SupportPromptLaunchRange.random(random)
        return true
    }
}

/** Asks for development support after a random number of launches until the user opts out for good. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SupportPrompt() {
    var visible by remember { mutableStateOf(SupportPromptSchedule.claimLaunch()) }

    fun dismiss() {
        SupportPromptSchedule.dismissForLaunch()
        visible = false
    }

    val uriHandler = LocalUriHandler.current
    val developers =
        remember {
            SupportedDeveloperHandles
                .mapNotNull { handle ->
                    AboutContributors.all.firstOrNull { it.githubHandle == handle && it.sponsorUrl != null }
                }.shuffled()
        }
    PopupVisibility(visible = visible) {
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text(stringResource(Res.string.support_prompt_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text(stringResource(Res.string.support_prompt_message))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        val sponsorLabel = stringResource(Res.string.sponsor_developer)
                        developers.forEach { developer ->
                            val shape = contributorShape(developer)
                            Column(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .clip(MaterialTheme.shapes.medium)
                                        .clickable(onClickLabel = sponsorLabel, role = Role.Button) {
                                            runCatching { uriHandler.openUri(checkNotNull(developer.sponsorUrl)) }
                                        }.padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AsyncImage(
                                    url = developer.avatarUrl,
                                    modifier = Modifier.size(56.dp).clip(shape),
                                    shape = shape,
                                )
                                Text(
                                    text = developer.name.substringBefore(' '),
                                    style = MaterialTheme.typography.labelLarge,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        Settings.shared.supportPromptDismissed = true
                        dismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Text(stringResource(Res.string.support_prompt_never_show))
                }
            },
        )
    }
}
