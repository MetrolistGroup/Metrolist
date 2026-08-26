package com.metrolist.music.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object IconUtils {
    fun setIcon(context: Context, enabled: Boolean) {
        val pm = context.packageManager
        val dynamic = ComponentName(context, "com.metrolist.music.MainActivityAlias")
        val static = ComponentName(context, "com.metrolist.music.MainActivityStatic")

        pm.setComponentEnabledSetting(
            dynamic,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
        pm.setComponentEnabledSetting(
            static,
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP
        )
    }
}
