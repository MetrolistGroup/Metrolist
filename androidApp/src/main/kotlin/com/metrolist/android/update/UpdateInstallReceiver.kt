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
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm == null || !AppUpdateManager.launchConfirmationPrompt(context, confirm)) {
                    AppUpdateManager.onInstallFinished(context = context, success = false, status = status, message = message)
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                AppUpdateManager.onInstallFinished(context = context, success = true, status = status, message = message)
            }

            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                AppUpdateManager.onInstallAborted(context)
            }

            else -> {
                AppUpdateManager.onInstallFinished(context = context, success = false, status = status, message = message)
            }
        }
    }
}

class CrashUpdateInstallReceiver : UpdateInstallReceiver()

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            AppUpdateManager.notifyPackageReplaced(context)
        }
    }
}
