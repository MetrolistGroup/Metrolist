package com.metrolist.android.utils

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration

fun Context.isAndroidTv(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
}

internal fun isAndroidAutomotive(
    hasAutomotiveFeature: Boolean,
    currentModeType: Int?,
): Boolean = hasAutomotiveFeature || currentModeType == Configuration.UI_MODE_TYPE_CAR

fun Context.isAndroidAutomotive(): Boolean {
    val uiModeManager = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    return isAndroidAutomotive(
        hasAutomotiveFeature = packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE),
        currentModeType = uiModeManager?.currentModeType,
    )
}

fun Context.startActivitySafely(intent: Intent): Boolean {
    if (intent.resolveActivity(packageManager) == null) return false
    return runCatching {
        startActivity(intent)
        true
    }.getOrDefault(false)
}
