package com.metrolist.android.utils

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

private const val OLD_FILE = "app_font.ttf"
private const val NEW_FILE = "app_font_12345678-1234-1234-1234-123456789abc.otf"

@OptIn(ExperimentalCoroutinesApi::class)
class CustomFontSelectionTest {
    @Test
    fun leavingSettingsAfterBackgroundImportKeepsThePreviouslySavedFont() =
        runTest {
            FontFiles().use { files ->
                val importer = launch { replace(files, prepare = { files.prepare().also { coroutineContext.cancel() } }) }
                importer.join()

                assertTrue(importer.isCancelled)
                assertEquals(OLD_FILE, files.selected)
                assertEquals("old", files.read(files.durable))
                assertFalse(files.exists(NEW_FILE))
                assertEquals("preset", files.read("preset.ttf"))
            }
        }

    @Test
    fun successfulSelectionRetiresOldFileOnlyAfterDurableSelection() =
        runTest {
            FontFiles().use { files ->
                val result =
                    replace(files, flush = {
                        assertEquals("old", files.read(files.durable))
                        files.flush()
                    })

                assertEquals(NEW_FILE, result.getOrThrow().fileName)
                assertEquals(NEW_FILE, files.durable)
                assertEquals("new", files.read(files.durable))
                assertFalse(files.exists(OLD_FILE))
                assertEquals("preset", files.read("preset.ttf"))
            }
        }

    @Test
    fun rejectedFontKeepsPreviousSelectionAndDoesNotFlush() =
        runTest {
            FontFiles().use { files ->
                val result =
                    replace(files, prepare = { Result.failure(IOException("invalid font")) }, flush = { error("Nothing selected") })
                assertTrue(result.isFailure)
                assertEquals(OLD_FILE, files.selected)
                assertEquals("old", files.read(files.durable))
            }
        }

    @Test
    fun cancellationBeforeIoDoesNotPrepareOrRemoveAFile() =
        runTest {
            FontFiles().use { files ->
                val importer =
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        replace(files, prepare = { error("canceled before IO started") })
                    }
                importer.cancelAndJoin()

                assertEquals(OLD_FILE, files.selected)
                assertEquals("old", files.read(files.durable))
                assertFalse(files.exists(NEW_FILE))
            }
        }

    @Test
    fun selectedFilesSurviveAnUncertainOrFailedCommit() =
        runTest {
            for (failsDuringSelection in listOf(true, false)) {
                FontFiles().use { files ->
                    val failed = IOException("settings could not be stored")
                    val errors = mutableListOf<Exception>()
                    try {
                        val result =
                            replace(
                                files,
                                choose = {
                                    files.select(it)
                                    if (failsDuringSelection) throw failed
                                    OLD_FILE
                                },
                                flush = { throw failed },
                                onFailure = errors::add,
                            )
                        if (failsDuringSelection) fail("selection failure should propagate")
                        assertTrue(result.isSuccess)
                        assertEquals(listOf(failed), errors)
                    } catch (actual: IOException) {
                        assertTrue(failsDuringSelection)
                        assertEquals(failed, actual)
                    }
                    assertEquals("new", files.read(files.selected))
                    assertEquals("old", files.read(files.durable))
                }
            }
        }

    @Test
    fun cancellationDuringFlushKeepsTheNewlyReferencedFile() =
        runTest {
            FontFiles().use { files ->
                val importer =
                    launch {
                        replace(files, flush = {
                            coroutineContext.cancel()
                            assertEquals("old", files.read(files.durable))
                            files.flush()
                        })
                    }
                importer.join()

                assertTrue(importer.isCancelled)
                assertEquals(NEW_FILE, files.durable)
                assertEquals("new", files.read(files.durable))
                assertFalse(files.exists(OLD_FILE))
            }
        }

    @Test
    fun delayedResetCleanupDoesNotDeleteAConcurrentNewImport() =
        runTest {
            FontFiles().use { files ->
                val beforeReset = files.selected
                files.selected = ""
                retireCustomFont(
                    fileName = beforeReset,
                    flushSelection = {
                        files.select(files.prepare().getOrThrow())
                        files.flush()
                    },
                    remove = files::remove,
                    onCleanupFailure = { throw it },
                    ioDispatcher = StandardTestDispatcher(testScheduler),
                )

                assertEquals("new", files.read(files.durable))
                assertFalse(files.exists(OLD_FILE))
            }
        }

    private suspend fun TestScope.replace(
        files: FontFiles,
        prepare: () -> Result<AppFontManager.ImportedFont> = files::prepare,
        choose: (AppFontManager.ImportedFont) -> String = files::select,
        flush: () -> Unit = files::flush,
        onFailure: (Exception) -> Unit = { throw it },
    ) = selectCustomFont(prepare, choose, flush, files::remove, onFailure, StandardTestDispatcher(testScheduler))

    private class FontFiles : AutoCloseable {
        private val directory = Files.createTempDirectory("font-selection").toFile()
        var selected: String = OLD_FILE
        var durable: String = OLD_FILE

        init {
            directory.resolve(OLD_FILE).writeText("old")
            directory.resolve("preset.ttf").writeText("preset")
        }

        fun prepare(): Result<AppFontManager.ImportedFont> {
            directory.resolve(NEW_FILE).writeText("new")
            return Result.success(AppFontManager.ImportedFont(NEW_FILE, "New"))
        }

        fun select(font: AppFontManager.ImportedFont): String = selected.also { selected = font.fileName }

        fun flush() {
            durable = selected
        }

        fun exists(file: String): Boolean = directory.resolve(file).isFile

        fun read(file: String): String = directory.resolve(file).readText()

        fun remove(file: String) {
            directory.resolve(file).delete()
        }

        override fun close() {
            directory.deleteRecursively()
        }
    }
}
