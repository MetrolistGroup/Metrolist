package com.metrolist.desktop.utils

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopJvmErrorFileTest {
    @Test
    fun childJvmUsesTheLogDirectoryDespitePackagedDefaults() {
        val temporary = Files.createTempDirectory("jvm-error-file").toFile()
        try {
            for (name in listOf("state with spaces", "state%p with a'quote\"")) {
                val directory = File(temporary, name)
                val java = File(System.getProperty("java.home"), "bin/${if (File.separatorChar == '\\') "java.exe" else "java"}")
                val builder = ProcessBuilder(java.path, "-XX:ErrorFile=packaged-default.log", "-XX:+PrintFlagsFinal", "-version")
                builder.environment().remove("JAVA_TOOL_OPTIONS")
                builder.environment().remove("JDK_JAVA_OPTIONS")
                builder.environment()["_JAVA_OPTIONS"] = "-Dmetrolist.crash.test=true"
                builder.withJvmErrorFile(directory)
                val output = File(temporary, "flags.txt")
                val process = builder.redirectErrorStream(true).redirectOutput(output).start()
                try {
                    assertTrue(process.waitFor(20, TimeUnit.SECONDS))
                    assertEquals(0, process.exitValue(), output.readText())
                    val flag = output.readLines().single { it.trimStart().startsWith("ccstr ErrorFile ") }
                    val actual = flag.substringAfter('=').substringBefore("{product}").trim()
                    assertEquals(jvmErrorFilePattern(directory), actual)
                    assertTrue(builder.environment().getValue("_JAVA_OPTIONS").startsWith("-Dmetrolist.crash.test=true "))
                } finally {
                    process.destroyForcibly()
                }
            }
        } finally {
            temporary.deleteRecursively()
        }
    }
}
