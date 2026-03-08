package com.metrolist.android.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat

open class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null || runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure) {
                    AppUpdateManager.onInstallFinished(success = false)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdateManager.onInstallFinished(success = true)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppUpdateManager.onInstallAborted()
            }

            else -> {
                AppUpdateManager.onInstallFinished(success = false)
            }
        }
    }
}

class CrashUpdateInstallReceiver : UpdateInstallReceiver()
