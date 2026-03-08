/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.desktop.utils

import com.metrolist.shared.settings.Settings
import com.metrolist.shared.utils.XdgUtils
import com.sun.jna.Library
import com.sun.jna.Native
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.Properties
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

object WaylandScale {
    private const val MAX_COMMAND_OUTPUT_CHARS = 64 * 1_024
    private val TRUSTED_EXECUTABLE_DIRECTORIES = listOf("/usr/bin", "/bin", "/usr/local/bin")
    private val LOG_FILE: File = File(XdgUtils.getXdgCacheHome(), "metrolist/wayland_scale.log")
    private var applied = false
    private val probeBudget = ThreadLocal<ProbeBudget>()
    private val monitorCache = AtomicReference<List<Monitor>>(emptyList())
    private val fallbackScale = AtomicReference<Float?>(null)
    private var watcherThread: Thread? = null
    private val refreshRequested = Semaphore(0)
    private val outputPattern = Regex("""Output:.*?(?=Output:|\z)""", RegexOption.DOT_MATCHES_ALL)
    private val geometryPattern = Regex("""Geometry:\s*(-?\d+),(-?\d+)\s+(\d+)x(\d+)""")
    private val scalePattern = Regex("""Scale:\s*([\d.]+)""")

    fun requestMonitorRefresh() {
        if (watcherThread != null && refreshRequested.availablePermits() == 0) refreshRequested.release()
    }

    interface LibC : Library {
        fun setenv(
            name: String,
            value: String,
            overwrite: Int,
        ): Int
    }

    fun detectAndApply() {
        if (applied) return
        if (isWayland() && Settings.shared.disableWaylandScaleFix) {
            log("Wayland scale fix disabled by settings")
            applied = true
            return
        }

        val forceScale = forcedScale()

        if (isWayland() && usesNativeWayland()) {
            System.setProperty("awt.toolkit.name", "WLToolkit")
            System.setProperty("jdk.gtk.version", "3")
            System.setProperty("sun.java2d.uiScale.enabled", "true")
            if (forceScale != null) System.setProperty("sun.java2d.uiScale", forceScale.toString())
            applied = true
            return
        }

        val scale =
            withProbeBudget {
                val detectedScale = forceScale ?: detectScale()
                if (isWayland()) applyCursorTheme()
                detectedScale
            }
        if (scale != null && scale in 0.1f..10.0f) {
            applyScaleProperties(scale, forceScale, isWayland())
            fallbackScale.set(scale)
            applied = true
            log("Applied Wayland scale: $scale")
        } else {
            log("Wayland scale could not be detected")
        }

        if (isWayland()) startMonitorWatcher()
    }

    internal fun applyScaleProperties(
        scale: Float,
        forceScale: Float?,
        wayland: Boolean,
        properties: Properties = System.getProperties(),
    ) {
        // X11 window geometry uses AWT's native scale, including saved sizes and fixed-size dialogs.
        if (wayland || forceScale != null) {
            properties.setProperty("sun.java2d.uiScale", if (forceScale == null) "1" else forceScale.toString())
        }
        properties.setProperty("skiko.uiScale", scale.toString())
    }

    private fun usesNativeWayland(): Boolean =
        System.getProperty("metrolist.useWakefield") == "true" || System.getenv("METROLIST_USE_WAKEFIELD") == "1"

    private fun forcedScale(): Float? =
        parseScale(System.getProperty("metrolist.forceScale")) ?: parseScale(System.getenv("METROLIST_FORCE_SCALE"))

    fun isWayland(): Boolean {
        val sessionType = System.getenv("XDG_SESSION_TYPE")
        val waylandDisplay = System.getenv("WAYLAND_DISPLAY")
        return sessionType == "wayland" || waylandDisplay != null
    }

    fun ensureDisplay(): Boolean {
        System.setProperty("java.awt.headless", "false")

        val currentDisplay = System.getenv("DISPLAY")
        if (!currentDisplay.isNullOrBlank()) {
            System.setProperty("DISPLAY", currentDisplay)
            return true
        }

        if (System.getProperty("metrolist.useWakefield") == "true" || System.getenv("METROLIST_USE_WAKEFIELD") == "1") return true
        val detectedDisplay =
            resolveSessionDisplay(
                runtimeDirectory = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank)?.let(::File),
                canConnect = { runCommand("xdpyinfo", "-display", it) != null },
            ) ?: return false
        try {
            val libc = Native.load("c", LibC::class.java) as LibC
            if (libc.setenv("DISPLAY", detectedDisplay, 1) != 0) return false
        } catch (e: Throwable) {
            log("Failed to set native DISPLAY: ${e.message}")
            return false
        }
        System.setProperty("DISPLAY", detectedDisplay)
        updateJavaEnv("DISPLAY", detectedDisplay)
        log("Ensured DISPLAY=$detectedDisplay")
        return true
    }

    internal fun resolveSessionDisplay(
        runtimeDirectory: File?,
        canConnect: (String) -> Boolean,
    ): String? {
        val display =
            runtimeDirectory
                ?.resolve("x11-display")
                ?.takeIf(File::isFile)
                ?.let { runCatching { it.readText().trim() }.getOrNull() } ?: return null
        return display.takeIf { Regex(":\\d+(?:\\.\\d+)?").matches(it) && canConnect(it) }
    }

    internal fun <T> withProbeBudget(
        timeoutMillis: Long = 2_000L,
        block: () -> T,
    ): T {
        probeBudget.set(ProbeBudget(timeoutMillis))
        return try {
            block()
        } finally {
            probeBudget.remove()
        }
    }

    private fun updateJavaEnv(
        name: String,
        value: String,
    ) {
        try {
            val processEnvClass = Class.forName("java.lang.ProcessEnvironment")
            val theEnvironmentField = processEnvClass.getDeclaredField("theEnvironment")
            theEnvironmentField.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val env = theEnvironmentField.get(null) as MutableMap<String, String>
            env[name] = value

            val theCaseInsensitiveEnvironmentField =
                runCatching { processEnvClass.getDeclaredField("theCaseInsensitiveEnvironment") }.getOrNull()
            if (theCaseInsensitiveEnvironmentField != null) {
                theCaseInsensitiveEnvironmentField.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val cienv = theCaseInsensitiveEnvironmentField.get(null) as MutableMap<String, String>
                cienv[name] = value
            }
        } catch (_: Throwable) {
        }
    }

    internal data class Monitor(
        val x: Int,
        val y: Int,
        val w: Int,
        val h: Int,
        val scale: Float,
    )

    fun getWindowScale(
        windowX: Int,
        windowY: Int,
        windowWidth: Int,
        windowHeight: Int,
    ): Float? {
        if (isWayland() && (Settings.shared.disableWaylandScaleFix || usesNativeWayland())) return null

        forcedScale()?.let { return it }

        // KWin exposes one global Xwayland coordinate scale, even with mixed-scale outputs.
        if (!isWayland() || isKde(System.getenv())) return fallbackScale.get()

        return monitorForWindow(monitorCache.get(), windowX, windowY, windowWidth, windowHeight)?.scale ?: fallbackScale.get()
    }

    internal fun monitorForWindow(
        monitors: List<Monitor>,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ): Monitor? =
        monitors
            .map { monitor ->
                val overlapWidth = (minOf(x.toLong() + width, monitor.x.toLong() + monitor.w) - maxOf(x, monitor.x)).coerceAtLeast(0)
                val overlapHeight = (minOf(y.toLong() + height, monitor.y.toLong() + monitor.h) - maxOf(y, monitor.y)).coerceAtLeast(0)
                monitor to overlapWidth * overlapHeight
            }.filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    private fun startMonitorWatcher() {
        if (watcherThread != null) return

        watcherThread =
            Thread({
                while (!Thread.interrupted()) {
                    try {
                        refreshMonitorCache()
                        if (refreshRequested.tryAcquire(30, TimeUnit.SECONDS)) {
                            Thread.sleep(2000)
                            refreshRequested.drainPermits()
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (_: Exception) {
                    }
                }
            }, "WaylandMonitorWatcher").apply {
                isDaemon = true
                start()
            }
    }

    fun refreshMonitorCache() {
        val desktop = System.getenv("XDG_CURRENT_DESKTOP")?.lowercase() ?: ""
        if (desktop.contains("gnome")) {
            queryGnomeState()?.let { updateMonitorCache(parseGnomeMonitors(it)) }
            return
        }
        if (desktop.contains("kde") || desktop.contains("plasma")) {
            withProbeBudget { detectScale() }?.let(fallbackScale::set)
            return
        }
        val json =
            if (desktop.contains("hyprland")) {
                runCommand("hyprctl", "monitors", "-j")
            } else if (desktop.contains("sway")) {
                runCommand("swaymsg", "-t", "get_outputs")
            } else {
                null
            }

        if (json != null) {
            runCatching { parseJsonMonitors(json, desktop.contains("sway")) }.getOrNull()?.let(::updateMonitorCache)
        }
    }

    internal fun parseJsonMonitors(
        output: String,
        sway: Boolean,
    ): List<Monitor> =
        Json.parseToJsonElement(output).jsonArray.mapNotNull { element ->
            val monitor = element as? JsonObject ?: return@mapNotNull null
            if (monitor["disabled"]?.jsonPrimitive?.booleanOrNull == true ||
                monitor["active"]?.jsonPrimitive?.booleanOrNull == false
            ) {
                return@mapNotNull null
            }
            val geometry = if (sway) monitor["rect"] as? JsonObject ?: return@mapNotNull null else monitor
            val scale =
                monitor["scale"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() && it > 0f }
                    ?: return@mapNotNull null
            val x = geometry["x"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            val y = geometry["y"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            var width = geometry["width"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            var height = geometry["height"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
            if (!sway) {
                if ((monitor["transform"]?.jsonPrimitive?.intOrNull ?: 0) % 2 != 0) {
                    val previousWidth = width
                    width = height
                    height = previousWidth
                }
                width = (width / scale).roundToInt()
                height = (height / scale).roundToInt()
            }
            if (width <= 0 || height <= 0) return@mapNotNull null
            Monitor(x, y, width, height, scale)
        }

    internal fun parseKScreenMonitors(output: String): List<Monitor> =
        outputPattern
            .findAll(output)
            .mapNotNull { match ->
                val geometry =
                    geometryPattern
                        .find(match.value)
                        ?.groupValues
                        ?: return@mapNotNull null
                val scale =
                    scalePattern
                        .find(match.value)
                        ?.groupValues
                        ?.get(1)
                        ?.let(::parseScale)
                        ?: return@mapNotNull null
                Monitor(
                    x = geometry[1].toIntOrNull() ?: return@mapNotNull null,
                    y = geometry[2].toIntOrNull() ?: return@mapNotNull null,
                    w = geometry[3].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null,
                    h = geometry[4].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null,
                    scale = scale,
                )
            }.toList()

    private fun updateMonitorCache(monitors: List<Monitor>) {
        if (monitors.isEmpty()) return
        monitorCache.set(monitors)
        fallbackScale.set(monitors.maxOf { it.scale })
    }

    internal fun resolveCursorSettings(
        environment: Map<String, String>,
        query: (String) -> String?,
        readConfig: () -> String?,
    ): Pair<String?, Int?> {
        val needsTheme = environment["XCURSOR_THEME"].isNullOrBlank()
        val needsSize = environment["XCURSOR_SIZE"].isNullOrBlank()
        if (!needsTheme && !needsSize) return null to null
        val alreadyProbed = environment["METROLIST_CURSOR_CONFIGURED"] == "1"
        var theme = if (needsTheme && !alreadyProbed) query("cursor-theme")?.trim()?.removeSurrounding("'") else null
        var size = if (needsSize && !alreadyProbed) query("cursor-size")?.trim()?.toIntOrNull()?.takeIf { it > 0 } else null
        if ((needsTheme && theme.isNullOrBlank()) || (needsSize && size == null)) {
            val config = readConfig().orEmpty()
            if (needsTheme && theme.isNullOrBlank()) {
                theme =
                    Regex("""(?m)^[\t ]*cursorTheme[\t ]*=[\t ]*(.*)$""")
                        .find(config)
                        ?.groupValues
                        ?.get(1)
                        ?.trim()
            }
            if (needsSize && size == null) {
                size =
                    Regex("""(?m)^[\t ]*cursorSize[\t ]*=[\t ]*(\d+)[\t ]*$""")
                        .find(config)
                        ?.groupValues
                        ?.get(1)
                        ?.toIntOrNull()
                        ?.takeIf { it > 0 }
            }
        }
        return theme?.takeIf(String::isNotBlank) to size
    }

    private fun applyCursorTheme() {
        try {
            val (theme, size) =
                resolveCursorSettings(
                    environment = System.getenv(),
                    query = { runCommand("gsettings", "get", "org.gnome.desktop.interface", it) },
                    readConfig = {
                        val configHome =
                            System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank)?.let(::File)
                                ?: File(System.getProperty("user.home"), ".config")
                        File(configHome, "kcminputrc").takeIf(File::isFile)?.readText()
                    },
                )
            if (theme == null && size == null) return
            val libc = Native.load("c", LibC::class.java) as LibC
            if (theme != null) {
                libc.setenv("XCURSOR_THEME", theme, 1)
                libc.setenv("GDK_CURSOR_THEME", theme, 1)
            }
            if (size != null) libc.setenv("XCURSOR_SIZE", size.toString(), 1)
        } catch (_: Throwable) {
        }
    }

    internal fun detectScale(
        environment: Map<String, String> = System.getenv(),
        wayland: Boolean = isWayland(),
        query: (String, List<String>) -> String? = { command, arguments -> runCommand(command, *arguments.toTypedArray()) },
        readConfig: () -> String? = {
            val configHome =
                environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(::File)
                    ?: File(System.getProperty("user.home"), ".config")
            runCatching { File(configHome, "kdeglobals").takeIf(File::isFile)?.readText() }.getOrNull()
        },
    ): Float? {
        val desktop = environment["XDG_CURRENT_DESKTOP"]?.lowercase().orEmpty()
        val kdeConfig = if (isKde(environment)) readConfig() else null
        // With client scaling disabled, KWin scales the Xwayland buffer itself.
        if (wayland && configValue(kdeConfig, "KScreen", "XwaylandClientsScale") == "false") return 1f

        if (wayland) {
            val compositorScale =
                when {
                    desktop.contains("gnome") -> detectScaleFromGnome(query)
                    desktop.contains("hyprland") -> detectScaleFromHyprland { query("hyprctl", listOf("monitors", "-j")) }
                    isKde(environment) -> detectScaleFromKScreen { query("kscreen-doctor", listOf("-o")) }
                    desktop.contains("sway") -> detectScaleFromSway { query("swaymsg", listOf("-t", "get_outputs")) }
                    else -> null
                }
            compositorScale?.let { return it }
        }

        val environmentScale = parseEnvironmentScale(environment)
        environmentScale?.takeIf { it != 1f }?.let { return it }
        val dpiScale = parseXftDpi(query("xrdb", listOf("-query")))
        dpiScale?.takeIf { it != 1f }?.let { return it }
        parseScale(configValue(kdeConfig, "KScreen", "ScaleFactor"))?.let { return it }
        if (dpiScale != null || environmentScale != null) return dpiScale ?: environmentScale
        if (!wayland) return null

        return detectScaleFromGsettings { query("gsettings", listOf("get", "org.gnome.desktop.interface", "scaling-factor")) }
            ?: detectScaleFromWlrRandr { query("wlr-randr", emptyList()) }
    }

    private fun isKde(environment: Map<String, String>): Boolean =
        environment["XDG_CURRENT_DESKTOP"]?.lowercase()?.let { it.contains("kde") || it.contains("plasma") } == true

    internal fun parseScale(value: String?): Float? = value?.trim()?.toFloatOrNull()?.takeIf { it in 0.1f..10f }

    internal fun parseEnvironmentScale(environment: Map<String, String>): Float? {
        val qtScale = parseScale(environment["QT_SCALE_FACTOR"])
        val gdkScale = parseScale(environment["GDK_SCALE"])
        val gdkDpiScale = parseScale(environment["GDK_DPI_SCALE"])
        val gtkScale =
            if (gdkScale != null || gdkDpiScale != null) {
                ((gdkScale ?: 1f) * (gdkDpiScale ?: 1f)).takeIf { it in 0.1f..10f }
            } else {
                null
            }
        val scales = listOfNotNull(qtScale, gtkScale, parseScale(environment["XDG_SCALE"]))
        return scales.firstOrNull { it != 1f } ?: scales.firstOrNull()
    }

    internal fun parseXftDpi(output: String?): Float? =
        output?.let {
            Regex("""(?m)^\s*Xft\.dpi\s*:\s*([^\s]+)\s*$""")
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.toFloatOrNull()
                ?.div(96f)
                ?.takeIf { scale -> scale in 0.1f..10f }
        }

    internal fun configValue(
        config: String?,
        group: String,
        key: String,
    ): String? {
        var currentGroup = ""
        for (line in config.orEmpty().lineSequence().map(String::trim)) {
            if (line.startsWith("[")) currentGroup = line
            if (currentGroup == "[$group]" && line.substringBefore('=').trim() == key && '=' in line) {
                return line.substringAfter('=').trim()
            }
        }
        return null
    }

    internal fun detectScaleFromHyprland(query: () -> String? = { runCommand("hyprctl", "monitors", "-j") }): Float? {
        val monitors = query()?.let { runCatching { parseJsonMonitors(it, sway = false) }.getOrNull() } ?: return null
        updateMonitorCache(monitors)
        return monitors.maxOfOrNull { it.scale }
    }

    private fun detectScaleFromGsettings(query: () -> String?): Float? =
        query()
            ?.trim()
            ?.removePrefix("uint32 ")
            ?.toFloatOrNull()
            ?.takeIf { it > 0f }

    private fun detectScaleFromKScreen(query: () -> String?): Float? = query()?.let(::parseKScreenMonitors)?.maxOfOrNull { it.scale }

    private fun detectScaleFromWlrRandr(query: () -> String?): Float? =
        query()?.let {
            Regex("""scale:\s*([\d.]+)""")
                .findAll(it)
                .mapNotNull { m -> parseScale(m.groupValues[1]) }
                .maxOrNull()
        }

    private fun detectScaleFromSway(query: () -> String?): Float? =
        query()?.let {
            Regex(""""scale"\s*:\s*([\d.]+)""")
                .findAll(it)
                .mapNotNull { m -> parseScale(m.groupValues[1]) }
                .maxOrNull()
        }

    internal fun detectScaleFromGnome(
        query: (String, List<String>) -> String? = { command, arguments -> runCommand(command, *arguments.toTypedArray()) },
    ): Float? = queryGnomeState(query)?.let(::parseGnomeScale)

    private fun queryGnomeState(
        query: (String, List<String>) -> String? = { command, arguments ->
            runCommand(command, *arguments.toTypedArray())
        },
    ): String? =
        query(
            "gdbus",
            listOf(
                "call",
                "--session",
                "--dest",
                "org.gnome.Mutter.DisplayConfig",
                "--object-path",
                "/org/gnome/Mutter/DisplayConfig",
                "--method",
                "org.gnome.Mutter.DisplayConfig.GetCurrentState",
            ),
        )

    internal fun parseGnomeScale(output: String): Float? {
        // GetCurrentState stores the active scale in each logical monitor tuple, not in a 'scale' property.
        val logicalMonitor = Regex("""\(\s*-?\d+,\s*-?\d+,\s*([\d.]+),\s*(?:uint32\s+)?\d+,\s*(true|false),\s*\[""")
        val scales =
            logicalMonitor
                .findAll(output)
                .mapNotNull { match ->
                    val scale = match.groupValues[1].toFloatOrNull()?.takeIf { it in 0.1f..10.0f }
                    scale?.let { it to (match.groupValues[2] == "true") }
                }.toList()
        return scales.firstOrNull { it.second }?.first ?: scales.singleOrNull()?.first
    }

    internal fun parseGnomeMonitors(output: String): List<Monitor> {
        val physicalHeader = Regex("""\(\s*\(\s*'([^']+)'\s*,\s*'[^']*'\s*,\s*'[^']*'\s*,\s*'[^']*'\s*\)\s*,\s*\[""")
        val mode = Regex("""\(\s*'[^']+'\s*,\s*(\d+),\s*(\d+),\s*[\d.]+,\s*[\d.]+,\s*\[[^]]*],\s*\{([^}]*)}""")
        val headers = physicalHeader.findAll(output).toList()
        val dimensions =
            headers
                .mapIndexedNotNull { index, header ->
                    val block = output.substring(header.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: output.length)
                    val current =
                        mode.findAll(block).firstOrNull { Regex("""'is-current':\s*<true>""").containsMatchIn(it.groupValues[3]) }
                            ?: return@mapIndexedNotNull null
                    val width = current.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
                    val height = current.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
                    header.groupValues[1] to (width to height)
                }.toMap()
        val logical = Regex("""\(\s*(-?\d+),\s*(-?\d+),\s*([\d.]+),\s*(?:uint32\s+)?(\d+),\s*(?:true|false),\s*\[\s*\(\s*'([^']+)'""")
        val physicalLayout = Regex("""'layout-mode':\s*<(?:uint32\s+)?2>""").containsMatchIn(output)
        return logical
            .findAll(output)
            .mapNotNull { match ->
                val x = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val y = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val scale = match.groupValues[3].toFloatOrNull()?.takeIf { it in 0.1f..10f } ?: return@mapNotNull null
                val transform = match.groupValues[4].toIntOrNull() ?: return@mapNotNull null
                val (width, height) = dimensions[match.groupValues[5]] ?: return@mapNotNull null
                val divisor = if (physicalLayout) 1f else scale
                val w = ((if (transform % 2 == 0) width else height) / divisor).roundToInt()
                val h = ((if (transform % 2 == 0) height else width) / divisor).roundToInt()
                Monitor(x, y, w, h, scale).takeIf { w > 0 && h > 0 }
            }.toList()
    }

    internal fun runCommand(
        command: String,
        vararg arguments: String,
    ): String? {
        val budget = probeBudget.get() ?: ProbeBudget()
        if (budget.remainingNanos() == 0L) return null
        val executable =
            TRUSTED_EXECUTABLE_DIRECTORIES
                .asSequence()
                .map { File(it, command) }
                .firstOrNull { it.isFile && it.canExecute() } ?: return null
        return try {
            val process = ProcessBuilder(executable.absolutePath, *arguments).redirectErrorStream(true).start()
            val output = StringBuilder()
            val readerThread =
                Thread {
                    runCatching {
                        process.inputStream.bufferedReader().use { reader ->
                            val buffer = CharArray(1_024)
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                val remaining = MAX_COMMAND_OUTPUT_CHARS - output.length
                                if (remaining > 0) output.appendRange(buffer, 0, minOf(count, remaining))
                            }
                        }
                    }
                }.apply {
                    isDaemon = true
                    start()
                }

            if (!process.waitFor(budget.remainingNanos(), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly()
                readerThread.join(100)
                null
            } else {
                readerThread.join(100)
                if (readerThread.isAlive) {
                    null
                } else if (process.exitValue() == 0) {
                    output.toString()
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun log(message: String) {
        try {
            LOG_FILE.parentFile?.mkdirs()
            LOG_FILE.appendText("[${System.currentTimeMillis()}] $message\n")
        } catch (_: Exception) {
        }
    }
}

private class ProbeBudget(
    timeoutMillis: Long = 2_000L,
) {
    private val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    fun remainingNanos(): Long = (deadline - System.nanoTime()).coerceAtLeast(0L)
}
