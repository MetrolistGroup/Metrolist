/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.utils

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import com.metrolist.android.R
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.app_icon_arctic_haze
import com.metrolist.shared.generated.resources.app_icon_gradient
import com.metrolist.shared.generated.resources.app_icon_lavender_leap
import com.metrolist.shared.generated.resources.app_icon_metromallow
import com.metrolist.shared.generated.resources.app_icon_original
import com.metrolist.shared.generated.resources.app_icon_pride_special
import com.metrolist.shared.generated.resources.app_icon_static
import com.metrolist.shared.generated.resources.app_icon_zenith_black
import com.metrolist.shared.generated.resources.app_icon_zenith_white
import com.metrolist.shared.settings.Settings
import org.jetbrains.compose.resources.StringResource

private const val MANIFEST_PACKAGE = "com.metrolist.android"
private val LEGACY_ALIAS_NAMES = listOf("MainActivityDefault", "MainActivityDynamic", "MainActivityDynamicBg")

enum class AppIcon(
    val aliasName: String,
    val labelRes: StringResource,
    @DrawableRes val previewRes: Int,
    @DrawableRes val previewBackgroundRes: Int? = null,
    val enabledByDefault: Boolean = false,
) {
    ORIGINAL(
        aliasName = "MainActivityOriginal",
        labelRes = Res.string.app_icon_original,
        previewRes = R.drawable.ic_launcher_foreground,
        previewBackgroundRes = R.drawable.app_icon_original_background,
        enabledByDefault = true,
    ),
    ARCTIC_HAZE(
        aliasName = "MainActivityArcticHaze",
        labelRes = Res.string.app_icon_arctic_haze,
        previewRes = R.drawable.app_icon_arctic_haze_foreground,
        previewBackgroundRes = R.drawable.app_icon_arctic_haze_background,
    ),
    LAVENDER_LEAP(
        aliasName = "MainActivityLavenderLeap",
        labelRes = Res.string.app_icon_lavender_leap,
        previewRes = R.drawable.app_icon_lavender_leap_foreground,
        previewBackgroundRes = R.drawable.app_icon_lavender_leap_background,
    ),
    METROMALLOW(
        aliasName = "MainActivityMetromallow",
        labelRes = Res.string.app_icon_metromallow,
        previewRes = R.drawable.app_icon_metromallow_foreground,
        previewBackgroundRes = R.drawable.app_icon_metromallow_background,
    ),
    PRIDE_SPECIAL(
        aliasName = "MainActivityPrideSpecial",
        labelRes = Res.string.app_icon_pride_special,
        previewRes = R.drawable.app_icon_pride_special_foreground,
        previewBackgroundRes = R.drawable.app_icon_pride_special_background,
    ),
    STATIC(
        aliasName = "MainActivityKayeStatic",
        labelRes = Res.string.app_icon_static,
        previewRes = R.drawable.app_icon_static_foreground,
        previewBackgroundRes = R.drawable.app_icon_static_background,
    ),
    ZENITH_BLACK(
        aliasName = "MainActivityZenithBlack",
        labelRes = Res.string.app_icon_zenith_black,
        previewRes = R.drawable.app_icon_zenith_black_full,
    ),
    ZENITH_WHITE(
        aliasName = "MainActivityZenithWhite",
        labelRes = Res.string.app_icon_zenith_white,
        previewRes = R.drawable.app_icon_zenith_white_full,
    ),
    GRADIENT(
        aliasName = "MainActivityGradient",
        labelRes = Res.string.app_icon_gradient,
        previewRes = R.drawable.app_icon_gradient_full,
    ),
    ;

    companion object {
        fun fromPreference(value: String): AppIcon = entries.firstOrNull { it.name == value } ?: ORIGINAL
    }
}

object IconUtils {
    @Synchronized
    fun syncWithPreference(context: Context) = setIcon(context, AppIcon.fromPreference(Settings.shared.appIcon))

    @Synchronized
    fun setIcon(
        context: Context,
        icon: AppIcon,
    ) {
        val pm = context.packageManager
        val selectedComponent = icon.componentName(context)

        if (!pm.isEnabled(selectedComponent, icon.enabledByDefault)) {
            pm.setComponentEnabledSetting(
                selectedComponent,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
        }

        AppIcon.entries.filterNot { it == icon }.forEach { other ->
            val component = other.componentName(context)
            if (pm.isEnabled(component, other.enabledByDefault)) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }

        LEGACY_ALIAS_NAMES.forEach { aliasName ->
            val component = ComponentName(context, "$MANIFEST_PACKAGE.$aliasName")
            if (pm.getComponentEnabledSetting(component) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(
                    component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
        }
    }

    private fun AppIcon.componentName(context: Context) = ComponentName(context, "$MANIFEST_PACKAGE.$aliasName")

    private fun PackageManager.isEnabled(
        component: ComponentName,
        enabledByDefault: Boolean,
    ): Boolean =
        when (getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> enabledByDefault
            else -> false
        }
}
