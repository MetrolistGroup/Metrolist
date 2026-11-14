package com.metrolist.android.onboarding

import android.content.Context
import androidx.core.content.edit

class OnboardingManager(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val isComplete: Boolean
        get() = preferences.getBoolean(KEY_COMPLETE, false)

    fun complete() {
        preferences.edit { putBoolean(KEY_COMPLETE, true) }
    }

    companion object {
        private const val PREFERENCES_NAME = "metrolist_onboarding"
        private const val KEY_COMPLETE = "completed_v1"
    }
}
