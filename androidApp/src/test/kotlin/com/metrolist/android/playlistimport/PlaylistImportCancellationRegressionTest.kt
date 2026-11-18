package com.metrolist.android.playlistimport

import android.app.Application
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import com.metrolist.shared.playlistimport.ImportProgressState
import com.metrolist.shared.playlistimport.PlaylistImportStatusManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
@Config(manifest = Config.NONE, application = Application::class, sdk = [35])
class PlaylistImportCancellationRegressionTest {
    @Test
    fun destructionBeforeWorkerStartsAllowsAnotherImport() = verifyCancellation(startWorker = false)

    @Test
    fun destructionAfterWorkerStartsAllowsAnotherImport() = verifyCancellation(startWorker = true)

    private fun verifyCancellation(startWorker: Boolean) =
        runTest {
            val context = notificationContext()
            val serviceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val service = Robolectric.buildService(AndroidPlaylistImportService::class.java).get()
            ReflectionHelpers.setField(service, "serviceScope", serviceScope)
            ReflectionHelpers.setField(service, "mBase", context)
            service.onCreate()
            var entered = false
            var retryId: String? = null
            val id =
                AndroidPlaylistImportTransfer
                    .prepareForeground<Unit>(
                        context,
                        "Import",
                        "CSV",
                        PlaylistImportPreparationType.IMPORT_DATA,
                    ) {
                        entered = true
                        awaitCancellation()
                    }.getOrThrow()
            try {
                val intent = checkNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
                assertEquals(id, intent.getStringExtra(AndroidPlaylistImportTransfer.EXTRA_REQUEST_ID))
                service.onStartCommand(intent, 0, 1)
                if (startWorker) runCurrent()
                assertEquals(startWorker, entered)
                service.onDestroy()
                runCurrent()
                val retry =
                    AndroidPlaylistImportTransfer.prepareForeground<Unit>(
                        context,
                        "Retry",
                        "CSV",
                        PlaylistImportPreparationType.IMPORT_DATA,
                    ) { }
                retryId = retry.getOrNull()
                println(
                    "startWorker=$startWorker entered=$entered retrySuccess=${retry.isSuccess} status=${PlaylistImportStatusManager.activeImport.value?.state}",
                )
                assertTrue("A destroyed import service must not leave future imports blocked as busy", retry.isSuccess)
            } finally {
                retryId?.let(AndroidPlaylistImportTransfer::cancel)
                serviceScope.cancel()
                runCurrent()
                PlaylistImportStatusManager.activeImport.value?.let { PlaylistImportStatusManager.clear(it.id) }
                AndroidPlaylistImportTransfer.clearPreparationResult(id)
            }
        }

    @Test
    fun notificationCancelBeforeWorkerStartsCompletesCaller() = verifyNotificationCancel(startWorker = false)

    @Test
    fun notificationCancelAfterWorkerStartsCompletesCaller() = verifyNotificationCancel(startWorker = true)

    @Test
    fun timeoutBeforeWorkerStartsCompletesCaller() = verifyNotificationCancel(startWorker = false, timeout = true)

    @Test
    fun timeoutAfterWorkerStartsCompletesCaller() = verifyNotificationCancel(startWorker = true, timeout = true)

    private fun verifyNotificationCancel(
        startWorker: Boolean,
        timeout: Boolean = false,
    ) = runTest {
        val context = notificationContext()
        val serviceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val service = Robolectric.buildService(AndroidPlaylistImportService::class.java).get()
        ReflectionHelpers.setField(service, "serviceScope", serviceScope)
        ReflectionHelpers.setField(service, "mBase", context)
        service.onCreate()
        var entered = false
        val caller =
            async(start = CoroutineStart.UNDISPATCHED) {
                AndroidPlaylistImportTransfer.runForeground<Unit>(context, "Import", "CSV") {
                    entered = true
                    awaitCancellation()
                }
            }
        try {
            val start = checkNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
            service.onStartCommand(start, 0, 1)
            if (startWorker) runCurrent()
            assertEquals(startWorker, entered)
            val cancel =
                Intent(context, AndroidPlaylistImportService::class.java).apply {
                    action = AndroidPlaylistImportTransfer.ACTION_CANCEL
                    putExtra(
                        AndroidPlaylistImportTransfer.EXTRA_REQUEST_ID,
                        start.getStringExtra(AndroidPlaylistImportTransfer.EXTRA_REQUEST_ID),
                    )
                }
            if (timeout) service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else service.onStartCommand(cancel, 0, 2)
            runCurrent()
            println("notificationCancel startWorker=$startWorker timeout=$timeout entered=$entered callerCompleted=${caller.isCompleted}")
            assertTrue("Notification cancellation must complete the waiting import caller", caller.isCompleted)
            assertTrue(caller.await().exceptionOrNull() is CancellationException)
            if (timeout) assertTrue(PlaylistImportStatusManager.activeImport.value?.state is ImportProgressState.Failed)
            assertFalse(PlaylistImportStatusManager.activeImport.value?.isRunning == true)
        } finally {
            service.onDestroy()
            serviceScope.cancel()
            runCurrent()
            caller.cancel()
            PlaylistImportStatusManager.activeImport.value?.let { PlaylistImportStatusManager.clear(it.id) }
        }
    }

    @Test
    fun normallyCompletedWorkerReturnsItsResult() =
        runTest {
            val context = notificationContext()
            val serviceScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
            val service = Robolectric.buildService(AndroidPlaylistImportService::class.java).get()
            ReflectionHelpers.setField(service, "serviceScope", serviceScope)
            ReflectionHelpers.setField(service, "mBase", context)
            service.onCreate()
            val caller =
                async(start = CoroutineStart.UNDISPATCHED) {
                    AndroidPlaylistImportTransfer.runForeground(context, "Import", "CSV") { "completed" }
                }
            try {
                service.onStartCommand(checkNotNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService), 0, 1)
                runCurrent()
                assertTrue(caller.isCompleted)
                assertEquals("completed", caller.await().getOrThrow())
                assertFalse(PlaylistImportStatusManager.activeImport.value?.isRunning == true)
            } finally {
                service.onDestroy()
                serviceScope.cancel()
                runCurrent()
                caller.cancel()
                PlaylistImportStatusManager.activeImport.value?.let { PlaylistImportStatusManager.clear(it.id) }
            }
        }

    private fun notificationContext() =
        object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Suppress("DEPRECATION")
            private val labels =
                object : Resources(baseContext.assets, baseContext.resources.displayMetrics, baseContext.resources.configuration) {
                    override fun getString(id: Int): String = id.toString()

                    override fun getString(
                        id: Int,
                        vararg formatArgs: Any,
                    ): String = id.toString()
                }

            override fun getResources(): Resources = labels
        }
}
