package com.metrolist.desktop.utils

import com.metrolist.shared.utils.XdgUtils
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.system.exitProcess

internal fun configureDesktopJvmErrorFile() {
    if (System.getProperty("jpackage.app-path").isNullOrBlank() && System.getenv("APPIMAGE").isNullOrBlank()) return
    runCatching {
        val directory = File(XdgUtils.getXdgStateHome(), "metrolist/logs")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create JVM crash log directory: $directory" }
        val pattern = jvmErrorFilePattern(directory)
        val configured = ManagementFactory.getRuntimeMXBean().inputArguments.lastOrNull { it.startsWith("-XX:ErrorFile=") }
        if (configured?.substringAfter('=')?.let { File(it).path } == pattern) return

        ProcessBuilder(desktopRestartCommand())
            .prepareDesktopRelaunch()
            .withJvmErrorFile(directory)
            .inheritIO()
            .start()
        exitProcess(0)
    }.onFailure { System.err.println("Unable to configure JVM crash log: ${it.message}") }
}

internal fun jvmErrorFilePattern(directory: File): String = directory.absolutePath.replace("%", "%%") + File.separator + "hs_err_pid%p.log"

internal fun ProcessBuilder.withJvmErrorFile(directory: File): ProcessBuilder =
    apply {
        val option = "-XX:ErrorFile=${jvmErrorFilePattern(directory)}"
        val quotedOption = "\"${option.replace("\"", "\"'\"'\"")}\""
        environment()["_JAVA_OPTIONS"] = listOfNotNull(environment()["_JAVA_OPTIONS"], quotedOption).joinToString(" ")
    }
