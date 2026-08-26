package com.metrolist.music.navigation

import android.content.Context
import android.content.Intent

/** Shared contract for intents that return to the host app's launcher activity. */
object AppLaunch {
    const val ACTION_RECOGNITION = "com.metrolist.music.action.RECOGNITION"
    const val ACTION_OPEN_WIDGET_TARGET = "com.metrolist.music.action.OPEN_WIDGET_TARGET"
    const val EXTRA_AUTO_START_RECOGNITION = "auto_start_recognition"
    const val EXTRA_WIDGET_TARGET_TYPE = "widget_target_type"
    const val EXTRA_WIDGET_TARGET_ID = "widget_target_id"

    fun intent(context: Context): Intent =
        context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(Intent.ACTION_MAIN).setPackage(context.packageName)
}
