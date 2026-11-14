package com.metrolist.desktop.utils

import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopFontManagerTest {
    @Test
    fun verifiesDownloadedFontDigest() {
        val file = Files.createTempFile("metrolist-font", ".ttf")
        try {
            file.writeText("font")

            assertTrue(
                DesktopFontManager.hasSha256(
                    file.toFile(),
                    "795ea3efa43d0872b63bf0067be97553b46983e4f075097669391e9d15388ecc",
                ),
            )
            assertFalse(DesktopFontManager.hasSha256(file.toFile(), "0".repeat(64)))
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
