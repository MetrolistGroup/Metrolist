/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.api.lyrics.romanizer

import com.metrolist.shared.youtube.playback.PlaybackEventLog
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import meow.nyx.glyphmind.Dictionaries
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GlyphmindDictionaryWiringTest {
    private val pack = Dictionaries.mandarin
    private val packBytes by lazy {
        assertNotNull(javaClass.getResourceAsStream("/glyphmind/mandarin.bin")).use { it.readBytes() }
    }

    @Test
    fun installsVerifiedResponseBeforeCleaningLegacyDirectoryAndReusesItOffline() =
        runBlocking {
            var requests = 0
            withFixture(
                MockEngine { request ->
                    requests++
                    assertEquals(pack.url, request.url.toString())
                    respond(packBytes)
                },
            ) { fixture ->
                assertTrue(fixture.prepare())
                assertContentEquals(packBytes, fixture.installed.readBytes())
                assertEquals("nǐ hǎo", fixture.romanizer.romanizeMandarin("你好"))
                assertFalse(fixture.legacy.exists())
                assertEquals(1, fixture.cleanupCalls)
                assertTrue(fixture.prepare())
                assertEquals(1, requests)
                HttpClient(MockEngine { error("An installed pack must load without a request") }).use { offline ->
                    val restarted = createDictionaryRomanizer(fixture.storage.absolutePath, offline)
                    assertTrue(LyricsRomanizer.prepare("你好", setOf("Chinese"), romanizer = { restarted }, removeLegacyDictionary = {}))
                    assertEquals("nǐ hǎo", restarted.romanizeMandarin("你好"))
                }
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun nonSuccessStatusPreservesLegacyAndCanRetry() =
        runBlocking {
            var status = HttpStatusCode.ServiceUnavailable
            withFixture(MockEngine { respond(packBytes, status) }) { fixture ->
                assertFalse(fixture.prepare())
                fixture.assertUninstalled()
                assertPreparationFailure("IllegalStateException")
                status = HttpStatusCode.OK
                assertTrue(fixture.prepare())
                assertFalse(fixture.legacy.exists())
                assertEquals(1, fixture.cleanupCalls)
            }
        }

    @Test
    fun rejectsOversizedContentLengthBeforeAcceptingAnOtherwiseValidPack() =
        runBlocking {
            withFixture(
                MockEngine {
                    respond(packBytes, headers = headersOf(HttpHeaders.ContentLength, (pack.size + 1).toString()))
                },
            ) { fixture ->
                assertFalse(fixture.prepare())
                fixture.assertUninstalled()
                assertPreparationFailure("IllegalStateException")
            }
        }

    @Test
    fun rejectsOversizedStreamWithoutContentLength() =
        runBlocking {
            withFixture(MockEngine { respond(packBytes.copyOf(packBytes.size + 1)) }) { fixture ->
                assertFalse(fixture.prepare())
                fixture.assertUninstalled()
                // The HTTP limit fails before the dictionary installer's size/hash validation.
                assertPreparationFailure("IllegalStateException")
            }
        }

    @Test
    fun propagatesCancellationWithoutCleaningOrLoggingAFailure() =
        runBlocking {
            withFixture(MockEngine { throw CancellationException("download cancelled") }) { fixture ->
                assertFailsWith<CancellationException> { fixture.prepare() }
                fixture.assertUninstalled()
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    @Test
    fun initializationFailureIsReportedWithoutCleaningLegacy() =
        runBlocking {
            withFixture(MockEngine { error("No request expected") }) { fixture ->
                assertFalse(
                    LyricsRomanizer.prepare(
                        "你好",
                        setOf("Chinese"),
                        romanizer = { throw IOException("Storage unavailable") },
                        removeLegacyDictionary = fixture::cleanup,
                    ),
                )
                fixture.assertUninstalled()
                assertPreparationFailure("IOException")
            }
        }

    @Test
    fun noRequiredPackDoesNotFetchOrCleanLegacy() =
        runBlocking {
            withFixture(MockEngine { error("Disabled languages must not fetch a pack") }) { fixture ->
                assertFalse(fixture.prepare(emptySet()))
                fixture.assertUninstalled()
                assertTrue(PlaybackEventLog.events.value.isEmpty())
            }
        }

    private suspend fun withFixture(
        engine: MockEngine,
        block: suspend (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("glyphmind-wiring-").toFile()
        PlaybackEventLog.clear()
        try {
            HttpClient(engine) { install(HttpTimeout) }.use { client -> block(Fixture(root, client)) }
        } finally {
            root.deleteRecursively()
            PlaybackEventLog.clear()
        }
    }

    private fun assertPreparationFailure(exceptionType: String) {
        val event = PlaybackEventLog.events.value.single()
        assertTrue(event.message.startsWith("[Glyphmind] Dictionary preparation failed"))
        assertEquals(exceptionType, event.details["exceptionTypes"])
    }

    private inner class Fixture(
        root: File,
        client: HttpClient,
    ) {
        val storage = File(root, "glyphmind")
        val legacy = File(root, "japanese-dictionary").apply { mkdirs() }
        val legacyFile = File(legacy, "old-pack").apply { writeText("retained until success") }
        val installed = File(storage, "${pack.id}/pack.bin")
        val romanizer = createDictionaryRomanizer(storage.absolutePath, client)
        var cleanupCalls = 0

        suspend fun prepare(languages: Set<String> = setOf("Chinese")): Boolean =
            LyricsRomanizer.prepare("你好", languages, romanizer = { romanizer }, removeLegacyDictionary = ::cleanup)

        fun cleanup() {
            assertContentEquals(packBytes, installed.readBytes())
            cleanupCalls++
            assertTrue(legacy.deleteRecursively())
        }

        fun assertUninstalled() {
            assertFalse(installed.exists())
            assertFalse(File(storage, ".${pack.id}.staging").exists())
            assertEquals("retained until success", legacyFile.readText())
            assertEquals(0, cleanupCalls)
        }
    }
}
