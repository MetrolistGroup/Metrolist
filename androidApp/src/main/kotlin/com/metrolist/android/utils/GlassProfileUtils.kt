package com.metrolist.android.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

const val DEFAULT_PROFILE_ID = "default"
const val hideStatusBarKey = "hideStatusBar"
const val activeProfileKey = "activeProfile"

fun isProfileIdSafe(profileId: String): Boolean =
    profileId.all { it.isLetterOrDigit() || it == '_' || it == '-' }

fun Context.getActiveProfile(): String =
    getSharedPreferences("profile_prefs", Context.MODE_PRIVATE)
        .getString(activeProfileKey, DEFAULT_PROFILE_ID) ?: DEFAULT_PROFILE_ID

fun Context.profilePreferences(profileId: String): SharedPreferences =
    getSharedPreferences("profile_$profileId", Context.MODE_PRIVATE)

@Composable
fun <T> rememberPreference(key: String, defaultValue: T): State<T> {
    return remember { mutableStateOf(defaultValue) }
}

fun Context.isProfileIdSafe(profileId: String): Boolean =
    com.metrolist.android.utils.isProfileIdSafe(profileId)
