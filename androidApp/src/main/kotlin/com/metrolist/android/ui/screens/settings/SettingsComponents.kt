/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.android.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.components.ExpressiveSwitch as SharedExpressiveSwitch

@Composable
fun switchSettingsItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    isBeta: Boolean = false,
    wrapTitle: Boolean = false,
): SettingsSubScreenItem =
    SettingsSubScreenItem(
        title = title,
        description = description,
        enabled = enabled,
        isBeta = isBeta,
        wrapTitle = wrapTitle,
        trailingContent = {
            ExpressiveSwitch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.clearAndSetSemantics { },
            )
        },
        checked = checked,
        onClick =
            if (enabled) {
                { onCheckedChange(!checked) }
            } else {
                null
            },
    )

@Composable
internal fun ExpressiveSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val isTvRow = LocalSettingsRowIsTv.current
    SharedExpressiveSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.focusProperties { canFocus = !isTvRow },
        enabled = enabled,
    )
}

@Composable
fun <T> listChooserSettingsItem(
    title: String,
    description: String? = null,
    options: List<T>,
    selectedOption: T,
    optionToString: @Composable (T) -> String = { it.toString() },
    optionIcon: (@Composable (T) -> Unit)? = null,
    enabled: Boolean = true,
    isBeta: Boolean = false,
    onOptionSelected: (T) -> Unit,
): SettingsSubScreenItem {
    var expanded by remember { mutableStateOf(false) }
    var anchorOffset by remember { mutableStateOf(IntOffset.Zero) }

    return SettingsSubScreenItem(
        title = title,
        description = description ?: optionToString(selectedOption),
        enabled = enabled,
        isBeta = isBeta,
        modifier = Modifier.trackPressPosition { anchorOffset = it.round() },
        menuContent = {
            ExpressiveSelectionMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                anchorOffset = anchorOffset,
                options = options,
                selectedOption = selectedOption,
                optionLabel = optionToString,
                optionIcon = optionIcon,
                onOptionSelected = onOptionSelected,
            )
        },
        onClick =
            if (enabled) {
                { expanded = true }
            } else {
                null
            },
    )
}
