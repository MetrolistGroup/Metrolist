package com.metrolist.shared.playback

import com.metrolist.innertubex.InnerTube
import com.metrolist.shared.auth.invalidateYouTubeSession
import com.metrolist.shared.constants.QueuedYouTubeHistoryJsonKey
import com.metrolist.shared.settings.Settings
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.russhwolf.settings.Settings as KmpSettings

class PendingYouTubePlaybackReportStoreTest {
    @Test
    fun logoutSerializesWithAnInFlightWriteAndRejectsOldSessionsAfterRelogin() =
        runBlocking {
            val writing = CountDownLatch(1)
            val releaseWrite = CountDownLatch(1)
            val clearingCredentials = CountDownLatch(1)
            val blockWrite = AtomicBoolean(false)
            val values = MapSettings()
            val storage =
                object : KmpSettings by values {
                    override fun putString(
                        key: String,
                        value: String,
                    ) {
                        if (key == QueuedYouTubeHistoryJsonKey && blockWrite.compareAndSet(true, false)) {
                            writing.countDown()
                            check(releaseWrite.await(5, TimeUnit.SECONDS))
                        }
                        if (key == "innerTubeCookie" && value.isEmpty()) clearingCredentials.countDown()
                        values.putString(key, value)
                    }
                }
            Settings.replaceSharedForTest(Settings(storage))
            Settings.shared.innerTubeCookie = "SAPISID=original"
            val client = HttpClient(MockEngine { error("Logout must not make a network request") })
            val innerTube = InnerTube(client).apply { replaceSession("SAPISID=original", "visitor", "account", "0", true) }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val generation = PendingYouTubePlaybackReportStore.generation
                val report = PendingYouTubePlaybackReport("old-session", "account", "video", "old-cpn", null, emptyList())
                val ping = PendingYouTubePlaybackPing.Start(0, 0)
                blockWrite.set(true)
                val append = executor.submit<Boolean> { PendingYouTubePlaybackReportStore.append(report, ping, generation) }
                assertTrue(writing.await(5, TimeUnit.SECONDS))
                val logout =
                    executor.submit {
                        invalidateYouTubeSession(innerTube)
                    }
                assertTrue(clearingCredentials.await(5, TimeUnit.SECONDS))
                releaseWrite.countDown()
                assertTrue(append.get(5, TimeUnit.SECONDS))
                logout.get(5, TimeUnit.SECONDS)
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())

                innerTube.replaceSession("SAPISID=refreshed", "visitor", "account", "0", true)
                assertFalse(PendingYouTubePlaybackReportStore.append(report, ping, generation))
                assertTrue(PendingYouTubePlaybackReportStore.all().isEmpty())
                val newReport = report.copy(id = "new-session", clientPlaybackNonce = "new-cpn")
                assertTrue(PendingYouTubePlaybackReportStore.append(newReport, ping))
                assertEquals(listOf("new-session"), PendingYouTubePlaybackReportStore.all().map { it.id })
            } finally {
                releaseWrite.countDown()
                executor.shutdownNow()
                executor.awaitTermination(5, TimeUnit.SECONDS)
                client.close()
                Settings.replaceSharedForTest(null)
            }
        }
}
