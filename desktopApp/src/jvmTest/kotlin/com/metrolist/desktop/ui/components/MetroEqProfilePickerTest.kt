package com.metrolist.desktop.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class MetroEqProfilePickerTest {
    @Test
    fun disappearedProfileReturnsInvalidContent() {
        val directory = Files.createTempDirectory("metro-eq").toFile()
        try {
            val file = directory.resolve("profile.txt").apply { writeText("Preamp: -1 dB") }
            check(file.delete())
            assertEquals("", readMetroEqProfile(file))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun readsUtf8AtTheLimitAndRejectsOneExtraByte() {
        val directory = Files.createTempDirectory("metro-eq").toFile()
        try {
            val text = "山" + "a".repeat(999_997)
            val file = directory.resolve("profile.txt").apply { writeText(text) }
            assertEquals(text, readMetroEqProfile(file))
            file.appendText("a")
            assertEquals("", readMetroEqProfile(file))
        } finally {
            directory.deleteRecursively()
        }
    }
}
