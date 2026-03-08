package com.metrolist.android

import com.metrolist.android.backup.PendingRestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppStartupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun readersWaitForRestoreAndInitialization() =
        runTest {
            val startup = AppStartup()
            val restoreFinished = CompletableDeferred<Unit>()
            val events = mutableListOf<String>()
            val reader =
                launch {
                    startup.await()
                    events += "read"
                }
            val initialization =
                launch {
                    startup.initialize(
                        restore = {
                            restoreFinished.await()
                            events += "restore"
                        },
                        start = { events += "open" },
                    )
                }
            yield()
            assertFalse(startup.isReady)
            assertTrue(events.isEmpty())
            restoreFinished.complete(Unit)
            initialization.join()
            reader.join()
            assertTrue(startup.isReady)
            assertEquals(listOf("restore", "open", "read"), events)
        }

    @Test
    fun asynchronousHydrationKeepsReadersWaitingAfterInfrastructureInitialization() =
        runTest {
            val startup = AppStartup()
            val hydrated = CompletableDeferred<Unit>()
            var infrastructureReady = false
            var published = false
            val initialization =
                launch {
                    startup.initialize(restore = {}, start = {
                        infrastructureReady = true
                        hydrated.await()
                        published = true
                    })
                }
            yield()
            assertTrue(infrastructureReady)
            assertFalse(startup.isReady)
            val reader =
                async {
                    startup.await()
                    published
                }
            yield()
            assertFalse(reader.isCompleted)
            hydrated.complete(Unit)
            initialization.join()
            assertTrue(reader.await())
            assertTrue(startup.isReady)
        }

    @Test
    fun cancellationKeepsTheStartupGateClosed() =
        runTest {
            val startup = AppStartup()
            val cancellation = CancellationException("restore cancelled")
            var opened = false
            val failure = runCatching { startup.initialize({ throw cancellation }, { opened = true }) }.exceptionOrNull()
            assertSame(cancellation, failure)
            assertTrue(runCatching { startup.await() }.exceptionOrNull() is CancellationException)
            assertFalse(opened)
            assertFalse(startup.isReady)
        }

    @Test
    fun failedRestoreRollsBackBeforeFailingGateAndNeverOpensReaders() =
        runTest {
            val live = temporary.newFile("song.db").apply { writeText("old") }
            val backup = temporary.newFile("backup.db").apply { writeText("new") }
            val pending = PendingRestore(File(temporary.root, "pending"))
            pending.stage(backup, "new-settings")
            val startup = AppStartup()
            var settings = "old-settings"
            var opened = false
            supervisorScope {
                val initialization =
                    async {
                        startup.initialize(restore = {
                            pending.install(live, { settings }, { settings = it }) {
                                settings = "partial"
                                error("import failed")
                            }
                        }, start = { opened = true })
                    }
                assertTrue(runCatching { initialization.await() }.isFailure)
                assertTrue(runCatching { startup.await() }.isFailure)
            }
            assertFalse(opened)
            assertFalse(startup.isReady)
            assertEquals("old", live.readText())
            assertEquals("old-settings", settings)
        }
}
