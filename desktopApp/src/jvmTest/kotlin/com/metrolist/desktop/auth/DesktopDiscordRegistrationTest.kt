package com.metrolist.desktop.auth

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopDiscordRegistrationTest {
    @Test
    fun desktopEntryQuotesLauncherAndKeepsUrlAsOneArgument() {
        val launcher = "/opt/Metrolist żółć/AppImage"
        val entry = linuxDiscordDesktopEntry(launcher, "Metrolist")
        assertTrue("Exec=env \"$launcher\" %u\n" in entry)
        assertTrue("MimeType=x-scheme-handler/metrolist-auth;\n" in entry)
        assertEquals(
            listOf("xdg-mime", "default", "com.metrolist.desktop.discord-auth.desktop", "x-scheme-handler/metrolist-auth"),
            linuxDiscordRegistrationCommand(),
        )
    }

    @Test
    fun desktopEntryEscapesExecMetacharactersAndRejectsNewlines() {
        val entry = linuxDiscordDesktopEntry("/tmp/a\"b\$c`d\\e%f", "Metrolist")
        val escaped = "/tmp/a" + "\\\\" + "\"b" + "\\\\" + "\$c" + "\\\\" + "`d" + "\\\\\\\\" + "e%%f"
        assertTrue("Exec=env \"$escaped\" %u\n" in entry)
        assertFailsWith<IllegalArgumentException> { linuxDiscordDesktopEntry("/tmp/a\nExec=bad", "Metrolist") }
        assertFailsWith<IllegalArgumentException> { linuxDiscordDesktopEntry("/tmp/app", "Metrolist\nExec=bad") }
    }

    @Test
    fun appImageUsesPersistentPathAndNativeLaunchUsesEnvironmentWrapper() {
        val directory = Files.createTempDirectory("metrolist-registration ").toFile()
        try {
            fun executable(name: String) =
                File(directory, name).apply {
                    writeText("")
                    setExecutable(true)
                }
            val appImage = executable("Metrolist.AppImage")
            val native = executable("Metrolist.bin")
            val wrapper = executable("Metrolist")
            val java = executable("java")
            assertEquals(appImage, linuxDiscordLauncher(appImage.path, native.path))
            assertEquals(wrapper, linuxDiscordLauncher(null, native.path))
            assertNull(linuxDiscordLauncher(null, java.path))
            assertNull(linuxDiscordLauncher(null, "Metrolist"))
            assertNull(linuxDiscordLauncher(File(directory, "missing.AppImage").path, native.path))
        } finally {
            directory.deleteRecursively()
        }
    }
}
