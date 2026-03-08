/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import com.metrolist.shared.BuildConfig
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.redactDiagnosticText
import com.metrolist.shared.utils.redactDiagnosticUrl
import io.sentry.Attachment
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.protocol.Request
import io.sentry.protocol.SentryId

internal fun desktopSentryRelease(version: String): String = "com.metrolist.desktop@$version"

object SentryManager {
    // Even Sentry.isEnabled() loads the SDK; keep opted-out sessions independent of it.
    @Volatile
    var isEnabled: Boolean = false
        private set

    @Synchronized
    fun initialize() {
        applyEnabled(Settings.shared.sentryEnabled)
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        Settings.shared.sentryEnabled = enabled
        if (Settings.shared.desktopPendingOnboardingSentryConsent.isNotBlank()) {
            Settings.shared.desktopPendingOnboardingSentryConsent = enabled.toString()
        }
        applyEnabled(enabled)
    }

    private fun applyEnabled(enabled: Boolean) {
        if (!enabled || !isPackagedApplication()) {
            shutdown()
            return
        }
        if (isEnabled) return

        Sentry.init { options ->
            configureDesktopSentry(
                options = options,
                release = desktopSentryRelease(BuildConfig.APP_VERSION),
                environment = System.getenv("SENTRY_ENVIRONMENT") ?: "production",
            )
        }
        isEnabled = Sentry.isEnabled()
        Sentry.startSession()
        DesktopNativeCrashRecovery.reportPending()
    }

    internal fun reportPreviousExit(previous: PreviousDesktopExit): Boolean {
        if (!isEnabled) return false
        return capturePreviousDesktopExit(previous)
    }

    @Synchronized
    fun shutdown() {
        if (!isEnabled) return
        isEnabled = false
        Sentry.endSession()
        Sentry.close()
    }

    private fun isPackagedApplication(): Boolean {
        if (System.getenv("SENTRY_ENABLE_DEVELOPMENT").toBoolean()) return true
        val source =
            SentryManager::class.java.protectionDomain
                ?.codeSource
                ?.location ?: return false
        return source.protocol == "file" && source.path.endsWith(".jar", ignoreCase = true)
    }
}

private class PreviousDesktopTermination : IllegalStateException("Previous desktop process terminated before clean shutdown")

internal fun capturePreviousDesktopExit(previous: PreviousDesktopExit): Boolean =
    Sentry.captureException(PreviousDesktopTermination()) { scope ->
        scope.level = SentryLevel.ERROR
        scope.setTag("error.domain", "desktop_termination")
        scope.setTag("native.hs_err", previous.crashes.isNotEmpty().toString())
        scope.addAttachment(Attachment(previous.summary.encodeToByteArray(), "previous-desktop-exit.txt", "text/plain"))
    } != SentryId.EMPTY_ID

internal fun configureDesktopSentry(
    options: SentryOptions,
    release: String,
    environment: String,
    sourceRevision: String = BuildConfig.SOURCE_REVISION,
) {
    options.dsn = "https://fe4474514a54a507252b26c60e229016@o4511744603193344.ingest.de.sentry.io/4511744608436304"
    options.sampleRate = 1.0
    options.tracesSampleRate = 0.0
    options.isDebug = System.getenv("SENTRY_DEBUG").toBoolean()
    options.release = release
    options.environment = environment
    options.setTag("desktop.os", System.getProperty("os.name"))
    options.setTag("desktop.os_version", System.getProperty("os.version"))
    options.setTag("desktop.arch", System.getProperty("os.arch"))
    sourceRevision.takeIf(String::isNotBlank)?.let { options.setTag("source_revision", it) }
    options.isAttachServerName = false
    options.isEnableUncaughtExceptionHandler = true
    options.isEnableAutoSessionTracking = false
    options.addInAppInclude("com.metrolist")
    options.beforeBreadcrumb =
        SentryOptions.BeforeBreadcrumbCallback { breadcrumb, _ ->
            sanitizeBreadcrumb(breadcrumb)
        }
    options.beforeSend =
        SentryOptions.BeforeSendCallback { event, _ ->
            event.takeUnless { it.getTag("playback.recovered") == "true" }?.let(::sanitizeSentryEvent)
        }
}

internal fun sanitizeSentryEvent(event: SentryEvent): SentryEvent {
    event.serverName = null
    event.request =
        event.request?.let { request ->
            Request().apply {
                url = request.url?.let(::redactDiagnosticUrl)
                method = request.method
            }
        }
    event.message?.let { message ->
        message.formatted = message.formatted?.let(::redactDiagnosticText)
        message.message = message.message?.let(::redactDiagnosticText)
        message.params = message.params?.map(::redactDiagnosticText)
    }
    event.throwable = null
    event.exceptions?.forEach { exception ->
        exception.value = exception.value?.let(::redactDiagnosticText)
        exception.stacktrace?.frames?.forEach { it.vars = null }
    }
    event.threads?.forEach { thread ->
        thread.name = thread.name?.let(::redactDiagnosticText)
        thread.stacktrace?.frames?.forEach { it.vars = null }
    }
    event.breadcrumbs?.forEach(::sanitizeBreadcrumb)
    event.extras = event.extras?.mapValues { (_, value) -> (value as? String)?.let(::redactDiagnosticText) ?: value }
    return event
}

internal fun sanitizeBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
    breadcrumb.message = breadcrumb.message?.let(::redactDiagnosticText)
    breadcrumb.data.keys.removeAll { it == "http.query" || it == "http.fragment" }
    breadcrumb.data.replaceAll { _, value -> (value as? String)?.let(::redactDiagnosticText) ?: value }
    return breadcrumb
}
