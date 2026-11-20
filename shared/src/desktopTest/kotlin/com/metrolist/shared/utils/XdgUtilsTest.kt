package com.metrolist.shared.utils

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class XdgUtilsTest {
    @Test
    fun missingEnvironmentUsesHomeDefaults() = verifyDirectories(null)

    @Test
    fun emptyEnvironmentUsesHomeDefaults() = verifyDirectories("")

    @Test
    fun relativeEnvironmentUsesHomeDefaults() = verifyDirectories("relative")

    @Test
    fun currentDirectoryEnvironmentUsesHomeDefaults() = verifyDirectories(".")

    @Test
    fun parentDirectoryEnvironmentUsesHomeDefaults() = verifyDirectories("../elsewhere")

    @Test
    fun unexpandedTildeEnvironmentUsesHomeDefaults() = verifyDirectories("~/data")

    @Test
    fun absoluteEnvironmentIsPreserved() = verifyDirectories("absolute")

    private fun verifyDirectories(value: String?) {
        val root = Files.createTempDirectory("metrolist-xdg-test").toFile()
        try {
            val home = File(root, "home")
            val absolute = File(root, "custom directory")
            val environmentValue = if (value == "absolute") absolute.path else value
            val expected =
                if (value == "absolute") {
                    List(4) { absolute.path }
                } else {
                    listOf(".local/share", ".cache", ".local/state", ".config").map { File(home, it).path }
                }
            val classPath =
                listOf(XdgUtils::class.java, XdgEnvironmentProbe::class.java, Unit::class.java)
                    .map {
                        File(
                            it.protectionDomain.codeSource.location
                                .toURI(),
                        ).absolutePath
                    }.distinct()
                    .joinToString(File.pathSeparator)
            val javaBin = File(System.getProperty("java.home"), "bin")
            val java = File(javaBin, "java").takeIf(File::isFile) ?: File(javaBin, "java.exe")
            val errorFile = File(root, "probe-stderr.log")
            val process =
                ProcessBuilder(
                    java.absolutePath,
                    "-Duser.home=${home.path}",
                    "-cp",
                    classPath,
                    XdgEnvironmentProbe::class.java.name,
                ).apply {
                    directory(root)
                    redirectError(errorFile)
                    listOf("XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_CONFIG_HOME").forEach {
                        if (environmentValue == null) environment().remove(it) else environment()[it] = environmentValue
                    }
                }.start()
            try {
                assertTrue(process.waitFor(15, TimeUnit.SECONDS), "XDG probe timed out")
                val output = process.inputStream.bufferedReader().readLines()
                assertEquals(0, process.exitValue(), output.joinToString("\n") + "\n" + errorFile.readText())
                assertEquals(expected, output, "XDG environment: $environmentValue")
            } finally {
                process.destroyForcibly()
            }
        } finally {
            root.deleteRecursively()
        }
    }
}

object XdgEnvironmentProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        listOf(
            XdgUtils.getXdgDataHome(),
            XdgUtils.getXdgCacheHome(),
            XdgUtils.getXdgStateHome(),
            XdgUtils.getXdgConfigHome(),
        ).forEach { println(it.absolutePath) }
    }
}
