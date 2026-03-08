package com.metrolist.android.utils

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import androidx.core.content.pm.PackageInfoCompat
import com.metrolist.android.BuildConfig
import com.metrolist.shared.listentogether.ListenTogetherManager
import com.metrolist.shared.utils.redactDiagnosticText
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val BYTES_PER_MB = 1024L * 1024L

internal fun buildListenTogetherDebugReport(
    context: Context,
    manager: ListenTogetherManager,
): String {
    val now = ZonedDateTime.now()
    val packageInfo = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    val resources = context.resources
    val metrics = resources.displayMetrics
    val config = resources.configuration
    val roomState = manager.roomState.value
    val logs = manager.logs.value

    return buildString {
        appendLine("=== Metrolist Listen Together debug report ===")
        appendLine()

        appendLine("-- Report --")
        appendLine("Date: ${now.toLocalDate()}")
        appendLine("Time: ${now.toLocalTime().withNano(0)}")
        appendLine("Local timestamp: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
        appendLine("UTC timestamp: ${Instant.now()}")
        appendLine("Time zone: ${ZoneId.systemDefault().id}")
        appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
        appendLine()

        appendLine("-- App --")
        appendLine("Package: ${context.packageName}")
        appendLine("Version: ${BuildConfig.VERSION_NAME} (code ${packageInfo?.let(PackageInfoCompat::getLongVersionCode) ?: "unknown"})")
        appendLine("Debug build: ${BuildConfig.DEBUG}")
        packageInfo?.let {
            appendLine("First installed: ${Instant.ofEpochMilli(it.firstInstallTime)}")
            appendLine("Last updated: ${Instant.ofEpochMilli(it.lastUpdateTime)}")
        }
        appendLine()

        appendLine("-- Device --")
        appendLine("Manufacturer / brand: ${Build.MANUFACTURER} / ${Build.BRAND}")
        appendLine("Model: ${Build.MODEL} (device ${Build.DEVICE}, product ${Build.PRODUCT})")
        appendLine("Hardware / board: ${Build.HARDWARE} / ${Build.BOARD}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Security patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("Build display: ${Build.DISPLAY}")
        appendLine("Incremental: ${Build.VERSION.INCREMENTAL}")
        appendLine("Fingerprint: ${Build.FINGERPRINT}")
        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine(
            "Screen: ${metrics.widthPixels}x${metrics.heightPixels}px, ${metrics.densityDpi}dpi, " +
                "${config.screenWidthDp}x${config.screenHeightDp}dp, smallest ${config.smallestScreenWidthDp}dp",
        )
        val orientation =
            if (config.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"
        appendLine("Orientation: $orientation, font scale ${config.fontScale}")
        runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
            appendLine(
                "Memory: ${memory.availMem / BYTES_PER_MB} MB free of ${memory.totalMem / BYTES_PER_MB} MB " +
                    "(low memory: ${memory.lowMemory}, low-RAM device: ${activityManager.isLowRamDevice})",
            )
        }
        appendLine("App heap limit: ${Runtime.getRuntime().maxMemory() / BYTES_PER_MB} MB")
        runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            appendLine("Battery saver: ${power.isPowerSaveMode}")
            appendLine("Ignoring battery optimizations: ${power.isIgnoringBatteryOptimizations(context.packageName)}")
        }
        runCatching {
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            val transports =
                listOf(
                    NetworkCapabilities.TRANSPORT_WIFI to "wifi",
                    NetworkCapabilities.TRANSPORT_CELLULAR to "cellular",
                    NetworkCapabilities.TRANSPORT_ETHERNET to "ethernet",
                    NetworkCapabilities.TRANSPORT_VPN to "vpn",
                ).filter { (type, _) -> capabilities?.hasTransport(type) == true }.joinToString { it.second }
            appendLine("Network: ${transports.ifEmpty { "none/unknown" }}")
            appendLine("Metered: ${capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false}")
            appendLine("VPN active: ${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true}")
        }
        appendLine()

        appendLine("-- Listen Together --")
        appendLine("Connection state: ${manager.connectionState.value}")
        appendLine("Server: ${manager.serverUrl.value}")
        appendLine("In room: ${manager.isInRoom.value}")
        appendLine("Role: ${manager.role.value}")
        appendLine("Users in room: ${roomState?.users?.size ?: 0}")
        appendLine("Join request pending: ${manager.joinRequestPending.value}")
        appendLine("Pending join requests: ${manager.pendingJoinRequests.value.size}")
        appendLine("Pending suggestions: ${manager.pendingSuggestions.value.size}")
        appendLine("Auto-approve joins: ${manager.autoApproveJoinRequests.value}")
        appendLine("Auto-approve suggestions: ${manager.autoApproveSongSuggestions.value}")
        appendLine("Sync host volume: ${manager.syncHostVolume.value}")
        appendLine("Blocked users: ${manager.blockedUsernames.value.size}")
        appendLine()

        appendLine("-- Connection log (${logs.size} entries) --")
        logs.forEach { entry ->
            appendLine("${entry.timestamp} - ${entry.level.name}: ${entry.message}")
        }
    }.let(::redactDiagnosticText)
}
