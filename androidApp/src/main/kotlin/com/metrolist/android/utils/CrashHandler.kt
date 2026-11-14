/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import com.metrolist.android.BuildConfig
import com.metrolist.android.ui.screens.CrashActivity
import com.metrolist.shared.crash.CrashReport
import com.metrolist.shared.crash.FatalCrashHandler
import com.metrolist.shared.crash.FileCrashStore
import com.metrolist.shared.settings.Settings
import java.io.File

object CrashHandler {
    const val EXTRA_CRASH_ID = "crash_id"
    const val ACTION_LOGS = "com.metrolist.music.action.CRASH_LOGS"
    const val EXTRA_FAILED_PROCESS = "failed_process"

    fun store(context: Context) = FileCrashStore(File(context.filesDir, "crashes"))

    fun record(
        context: Context,
        error: Throwable,
        thread: Thread = Thread.currentThread(),
    ): String {
        val report =
            CrashReport.create(
                "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                "Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT}) / ${Build.MANUFACTURER} ${Build.MODEL} / ${thread.name}",
                error,
            )
        store(context).save(report)
        return report.id
    }

    fun launch(
        context: Context,
        id: String,
        terminateAfterHandoff: Boolean = false,
    ) {
        context.startActivity(
            Intent(context, CrashActivity::class.java).apply {
                putExtra(EXTRA_CRASH_ID, id)
                if (terminateAfterHandoff) putExtra(EXTRA_FAILED_PROCESS, Process.myPid())
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            },
        )
    }

    fun logsIntent(context: Context): Intent = Intent(context, CrashActivity::class.java).setAction(ACTION_LOGS)

    fun install(context: Context) {
        val fallback = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            FatalCrashHandler(fallback) { thread, throwable ->
                launch(context, record(context, throwable, thread))
                Thread({ runCatching { Settings.shared.flush() } }, "CrashSettingsFlush").apply {
                    isDaemon = true
                    start()
                    join(500)
                }
                fallback?.uncaughtException(thread, throwable)
            },
        )
    }
}
