package com.metrolist.android.onboarding

import androidx.compose.ui.graphics.vector.ImageVector

internal enum class OnboardingDestination {
    HOME,
    LOGIN,
    BACKUP_RESTORE,
}

internal data class OnboardingAccount(
    val service: String,
    val displayName: String,
    val imageUrl: String?,
    val fallbackIcon: ImageVector,
    val fetchGoogleImage: Boolean = false,
    val fetchLastFmImage: Boolean = false,
)
