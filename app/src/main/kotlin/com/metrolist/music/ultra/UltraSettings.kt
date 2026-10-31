package com.metrolist.music.ultra

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import com.metrolist.music.ui.component.Material3SettingsItem
import ultra.hooks.UltraHooks

/**
 * Glue for seam S9 (SettingsScreen.kt): turns the entries contributed through [UltraHooks] into
 * rows of upstream's settings list. Returns an empty list when nothing is registered, which makes
 * the seam a no-op.
 */
@Composable
fun ultraSettingsItems(navController: NavController): List<Material3SettingsItem> {
    // Contributors are fixed after startup, so resolving once per composition entry is enough.
    val entries = remember { UltraHooks.settingsEntries() }
    return entries.map { entry ->
        Material3SettingsItem(
            icon = painterResource(entry.iconRes),
            title = { Text(stringResource(entry.titleRes)) },
            onClick = { navController.navigate(entry.route) },
        )
    }
}
