package com.metrolist.shared.update

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.metrolist.shared.BuildConfig
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.dismiss
import com.metrolist.shared.generated.resources.updates_action
import com.metrolist.shared.generated.resources.updates_available_title
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.theme.MetrolistTheme
import com.metrolist.shared.ui.theme.parseThemeSeedColor
import com.metrolist.shared.utils.openUrl
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

private const val CHECK_INTERVAL_MS = 60 * 60 * 1_000L

/** iOS installs come from sideloading tools, so the app only points to the new release, once per version. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun IosUpdateNotice() {
    var release by remember { mutableStateOf<LatestRelease?>(null) }
    LaunchedEffect(Unit) {
        val settings = Settings.shared
        val now = Clock.System.now().toEpochMilliseconds()
        val lastAttempt = settings.lastUpdateCheckAttemptTimestamp
        if (!settings.checkForUpdates || !settings.updateNotifications) return@LaunchedEffect
        if (lastAttempt in 1..now && now - lastAttempt < CHECK_INTERVAL_MS) return@LaunchedEffect
        settings.lastUpdateCheckAttemptTimestamp = now
        val client = HttpClient(Darwin)
        val found =
            try {
                val response = client.get(LATEST_RELEASE_URL) { header(HttpHeaders.Accept, "application/vnd.github+json") }
                newerLatestRelease(response.bodyAsText(), BuildConfig.APP_VERSION)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                client.close()
            }
        release = found?.takeUnless { it.version == settings.lastNotifiedUpdateVersion }
    }
    val available = release ?: return
    val dismiss = {
        Settings.shared.lastNotifiedUpdateVersion = available.version
        release = null
    }
    MetrolistTheme(
        seedColor = parseThemeSeedColor(Settings.shared.selectedThemeColor),
        darkTheme = Settings.shared.getCurrentThemeIsDark(),
        pureBlack = Settings.shared.isPureBlackTheme(),
    ) {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(stringResource(Res.string.updates_available_title, available.version)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        openUrl(available.htmlUrl)
                        dismiss()
                    },
                    shapes = ButtonDefaults.shapes(),
                ) { Text(stringResource(Res.string.updates_action)) }
            },
            dismissButton = {
                TextButton(onClick = dismiss, shapes = ButtonDefaults.shapes()) { Text(stringResource(Res.string.dismiss)) }
            },
        )
    }
}
