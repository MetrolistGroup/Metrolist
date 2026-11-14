package com.metrolist.desktop.utils

import com.metrolist.desktop.crash.DesktopCrashRecovery
import java.lang.management.ManagementFactory

internal fun desktopRestartCommand(): List<String> {
    val info = ProcessHandle.current().info()
    val classPath = System.getProperty("java.class.path", "")
    val mainCommand = System.getProperty("sun.java.command", "")
    return desktopRestartCommand(
        appImage = System.getenv("APPIMAGE"),
        jpackagePath = System.getProperty("jpackage.app-path"),
        command = info.command().orElse(null),
        processArguments = info.arguments().orElse(null)?.toList(),
        jvmArguments = ManagementFactory.getRuntimeMXBean().inputArguments,
        classPath = classPath,
        mainClass =
            if (classPath.endsWith(".jar") && (mainCommand == classPath || mainCommand.startsWith("$classPath "))) {
                classPath
            } else {
                mainCommand.substringBefore(' ')
            },
    )
}

internal fun desktopRestartCommand(
    appImage: String?,
    command: String?,
    processArguments: List<String>?,
    jvmArguments: List<String>,
    classPath: String,
    mainClass: String,
    jpackagePath: String? = null,
): List<String> {
    val launcher = appImage?.takeIf(String::isNotBlank) ?: jpackagePath?.takeIf(String::isNotBlank)
    val executable = requireNotNull(launcher ?: command) { "Current application command is unavailable" }
    val java = command?.substringAfterLast('/')?.substringAfterLast('\\')?.lowercase() in setOf("java", "java.exe", "javaw.exe")
    val arguments = processArguments.orEmpty()
    val launchArguments =
        if (launcher != null || !java) {
            if (java || command == null) emptyList() else arguments
        } else if (arguments.contains(mainClass) || arguments.indexOf("-jar").let { it >= 0 && it + 1 < arguments.size }) {
            arguments
        } else {
            require(classPath.isNotBlank() && mainClass.isNotBlank()) { "Cannot determine desktop restart command" }
            jvmArguments + if (mainClass.endsWith(".jar")) listOf("-jar", mainClass) else listOf("-cp", classPath, mainClass)
        }
    val recoveryIndex = launchArguments.indexOf(DesktopCrashRecovery.ARGUMENT)
    val restartArguments =
        if (recoveryIndex < 0) launchArguments else launchArguments.take(recoveryIndex) + launchArguments.drop(recoveryIndex + 2)
    return listOf(executable) + restartArguments
}

internal fun ProcessBuilder.prepareDesktopRelaunch(): ProcessBuilder =
    apply {
        environment().remove("_JPACKAGE_LAUNCHER")
    }
