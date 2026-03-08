@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.shared.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.repeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_name
import com.metrolist.shared.generated.resources.onboarding_notification_downloads
import com.metrolist.shared.generated.resources.onboarding_notification_playback
import com.metrolist.shared.generated.resources.onboarding_privacy_open_failed
import com.metrolist.shared.generated.resources.onboarding_privacy_policy
import com.metrolist.shared.generated.resources.onboarding_sentry_diagnostics
import com.metrolist.shared.generated.resources.onboarding_sentry_opt_in
import com.metrolist.shared.generated.resources.onboarding_sentry_private
import com.metrolist.shared.generated.resources.onboarding_step_of
import com.metrolist.shared.generated.resources.pause
import com.metrolist.shared.generated.resources.play
import com.metrolist.shared.ui.components.GroupedListItem
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.PredictiveNavigationContent
import com.metrolist.shared.ui.utils.verticalScrollWithDrag
import org.jetbrains.compose.resources.stringResource

enum class OnboardingPage {
    WELCOME,
    MIGRATION,
    MIGRATION_COMPLETE,
    CRASH_REPORTS,
    NOTIFICATIONS,
    LANGUAGE_LYRICS,
    PLAYBACK,
    FINISH,
}

data class OnboardingPageContent(
    val page: OnboardingPage,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val step: Int,
    val stepCount: Int,
    val primaryLabel: String,
    val secondaryLabel: String?,
    val isWorking: Boolean,
    val error: String?,
    val backLabel: String,
    val onPrimary: () -> Unit,
    val onSecondary: (() -> Unit)?,
    val onBack: (() -> Unit)?,
)

@Composable
fun OnboardingTransition(
    content: OnboardingPageContent,
    previousContent: OnboardingPageContent?,
    body: @Composable (OnboardingPageContent) -> Unit,
) {
    val history = remember { mutableMapOf<OnboardingPage, OnboardingPageContent>() }
    SideEffect {
        history[content.page] = content
        previousContent?.let { history[it.page] = it }
    }
    PredictiveNavigationContent(
        targetState = content.page,
        previousState = previousContent?.page ?: content.page,
        backEnabled = previousContent != null && content.onBack != null && !content.isWorking,
        onBack = { checkNotNull(content.onBack).invoke() },
        modifier = Modifier.fillMaxSize(),
    ) { page ->
        body(
            when (page) {
                content.page -> content
                previousContent?.page -> checkNotNull(previousContent)
                else -> history.getValue(page)
            },
        )
    }
}

@Composable
fun OnboardingContent(
    content: OnboardingPageContent,
    welcomeContent: @Composable (Modifier) -> Unit,
    featureContent: @Composable (OnboardingPage) -> Unit,
    modifier: Modifier = Modifier,
    preferredActionModifier: Modifier = Modifier,
    previousContent: OnboardingPageContent? = null,
) {
    var paused by rememberSaveable { mutableStateOf(false) }
    OnboardingTransition(content, previousContent) { page ->
        OnboardingPageLayout(page, welcomeContent, featureContent, modifier, preferredActionModifier, paused) { paused = it }
    }
}

@Composable
private fun OnboardingPageLayout(
    content: OnboardingPageContent,
    welcomeContent: @Composable (Modifier) -> Unit,
    featureContent: @Composable (OnboardingPage) -> Unit,
    modifier: Modifier = Modifier,
    preferredActionModifier: Modifier,
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
) {
    val welcome = content.page == OnboardingPage.WELCOME
    val stepDescription = stringResource(Res.string.onboarding_step_of, content.step, content.stepCount)

    Box(
        modifier.fillMaxSize().clipToBounds().semantics {
            paneTitle = content.title
            stateDescription = stepDescription
        },
    ) {
        AnimatedVisibility(visible = welcome, enter = fadeIn(tween(320)), exit = fadeOut(tween(320))) {
            WelcomeBackdrop(paused, onFinished = { onPausedChange(true) })
        }
        BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            val wide = maxWidth >= 840.dp && maxWidth > maxHeight
            val gutter = if (wide) 56.dp else 24.dp
            val stageWidth = minOf(maxWidth, 600.dp)
            val availableHeight = maxHeight
            Column(Modifier.width(stageWidth).fillMaxHeight().align(Alignment.Center)) {
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                    val pageContent = content
                    val pageWelcome = pageContent.page == OnboardingPage.WELCOME
                    val scrollState = rememberScrollState()
                    Column(
                        Modifier.fillMaxSize().background(if (pageWelcome) Color.Transparent else MaterialTheme.colorScheme.surface),
                    ) {
                        if (pageWelcome) {
                            Row(
                                Modifier.fillMaxWidth().padding(start = gutter, end = gutter - 12.dp, top = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                welcomeContent(Modifier.size(24.dp))
                                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { onPausedChange(!paused) }, shapes = IconButtonDefaults.shapes()) {
                                    Icon(
                                        if (paused) MetrolistIcons.Filled.PlayArrow else MetrolistIcons.Filled.Pause,
                                        contentDescription = stringResource(if (paused) Res.string.play else Res.string.pause),
                                    )
                                }
                            }
                        }
                        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                            val viewportHeight = maxHeight
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .verticalScrollWithDrag(scrollState)
                                    .heightIn(min = viewportHeight)
                                    .padding(horizontal = gutter, vertical = if (availableHeight < 500.dp) 16.dp else 32.dp),
                                verticalArrangement = if (pageWelcome) Arrangement.Bottom else Arrangement.Top,
                            ) {
                                if (!pageWelcome) {
                                    Icon(
                                        pageContent.icon,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(32.dp),
                                    )
                                    Spacer(Modifier.height(24.dp))
                                }
                                Text(
                                    stringResource(Res.string.onboarding_step_of, pageContent.step, pageContent.stepCount),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 8.dp),
                                )
                                Text(
                                    pageContent.title,
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 32.sp,
                                    lineHeight = 40.sp,
                                    modifier = Modifier.widthIn(max = 540.dp).semantics { heading() },
                                )
                                Text(
                                    pageContent.description,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 16.dp).widthIn(max = 540.dp),
                                )
                                val details =
                                    when (pageContent.page) {
                                        OnboardingPage.CRASH_REPORTS -> {
                                            listOf(
                                                MetrolistIcons.Outlined.Tune to stringResource(Res.string.onboarding_sentry_opt_in),
                                                MetrolistIcons.Outlined.Info to stringResource(Res.string.onboarding_sentry_diagnostics),
                                                MetrolistIcons.Rounded.Lock to stringResource(Res.string.onboarding_sentry_private),
                                            )
                                        }

                                        OnboardingPage.NOTIFICATIONS -> {
                                            listOf(
                                                MetrolistIcons.Outlined.PlayArrow to
                                                    stringResource(Res.string.onboarding_notification_playback),
                                                MetrolistIcons.Outlined.Download to
                                                    stringResource(Res.string.onboarding_notification_downloads),
                                            )
                                        }

                                        else -> {
                                            emptyList()
                                        }
                                    }
                                if (details.isNotEmpty()) {
                                    Column(Modifier.fillMaxWidth().padding(top = 32.dp)) {
                                        details.forEachIndexed { index, (icon, label) ->
                                            GroupedListItem(index = index, count = details.size) {
                                                Row(
                                                    Modifier.fillMaxWidth().padding(16.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                ) {
                                                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                    Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                                }
                                            }
                                        }
                                    }
                                }
                                if (pageContent.page == OnboardingPage.CRASH_REPORTS || pageWelcome) {
                                    Spacer(Modifier.height(16.dp))
                                    PrivacyLink()
                                }
                                featureContent(pageContent.page)
                                pageContent.error?.let {
                                    Text(
                                        it,
                                        color = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                    )
                                }
                            }
                        }
                    }
                }
                OnboardingBottomActions(
                    content,
                    preferredActionModifier,
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = availableHeight * 0.45f)
                        .verticalScrollWithDrag(rememberScrollState())
                        .padding(horizontal = gutter, vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun PrivacyLink() {
    val uriHandler = LocalUriHandler.current
    val privacyUrl = "https://metrolist.cc/privacy"
    var openFailed by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { openFailed = runCatching { uriHandler.openUri(privacyUrl) }.isFailure },
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(stringResource(Res.string.onboarding_privacy_policy))
        }
        if (openFailed) {
            Text(
                stringResource(Res.string.onboarding_privacy_open_failed),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
            SelectionContainer { Text(privacyUrl) }
        }
    }
}

@Composable
private fun OnboardingBottomActions(
    content: OnboardingPageContent,
    preferredActionModifier: Modifier,
    modifier: Modifier,
) {
    val secondaryWeight by animateFloatAsState(
        targetValue = if (content.secondaryLabel == null) 0f else 1f,
        animationSpec = tween(320),
        label = "Onboarding primary button width",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        content.onBack?.let { back ->
            IconButton(onClick = back, enabled = !content.isWorking, shapes = IconButtonDefaults.shapes()) {
                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowBack, contentDescription = content.backLabel)
            }
        }
        if (content.page != OnboardingPage.WELCOME) {
            content.secondaryLabel?.let { secondary ->
                TextButton(
                    onClick = content.onSecondary ?: {},
                    enabled = !content.isWorking,
                    modifier = preferredActionModifier.weight(secondaryWeight.coerceAtLeast(0.001f)).heightIn(min = 56.dp),
                    shapes = ButtonDefaults.shapes(),
                ) { Text(secondary) }
            } ?: run {
                if (secondaryWeight > 0f) Spacer(Modifier.weight(secondaryWeight))
            }
        }
        Button(
            onClick = content.onPrimary,
            enabled = !content.isWorking,
            modifier =
                (if (content.secondaryLabel == null) preferredActionModifier else Modifier)
                    .weight(if (content.page == OnboardingPage.WELCOME) 1f else 3f - secondaryWeight)
                    .heightIn(min = if (content.page == OnboardingPage.WELCOME) 64.dp else 56.dp),
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(content.primaryLabel)
            if (content.page == OnboardingPage.WELCOME) {
                Spacer(Modifier.width(8.dp))
                Icon(MetrolistIcons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WelcomeBackdrop(
    paused: Boolean,
    onFinished: () -> Unit,
) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val motionEnabled = !paused && lifecycleState == Lifecycle.State.RESUMED
    val motion = remember { Animatable(0f) }
    val morph = remember { Morph(MaterialShapes.Cookie7Sided, MaterialShapes.Cookie9Sided) }
    val path = remember { Path() }
    val outline = MaterialTheme.colorScheme.outlineVariant
    val accent = MaterialTheme.colorScheme.primary
    LaunchedEffect(motionEnabled, paused) {
        if (motionEnabled) {
            motion.animateTo(0f, tween((9000 * motion.value).toInt(), easing = FastOutSlowInEasing))
            motion.animateTo(1f, repeatable(2, tween(9000, easing = FastOutSlowInEasing), RepeatMode.Reverse))
            onFinished()
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val progress = motion.value
        val diameter = minOf(size.width * 1.15f, size.height * 0.9f)
        morph.toPath(progress, path)
        withTransform({
            translate(size.width * 0.8f - diameter / 2, -diameter * (0.48f - progress * 0.12f))
            scale(diameter, diameter, pivot = Offset.Zero)
            rotate(progress * 45f, pivot = Offset(0.5f, 0.5f))
        }) {
            drawPath(path, accent.copy(alpha = 0.06f))
            drawPath(path, outline, style = Stroke(1.5.dp.toPx() / diameter))
        }
        rotate(-24f + progress * 8f, pivot = Offset(size.width * 0.2f, size.height * 0.85f)) {
            drawOval(
                outline,
                topLeft = Offset(-size.width * 0.7f, size.height * (0.43f + progress * 0.04f)),
                size = Size(size.width * 2.3f, size.height * 1.3f),
                style = Stroke(1.5.dp.toPx()),
            )
        }
    }
}
