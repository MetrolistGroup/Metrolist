package com.metrolist.shared.ui.menu

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SelectableDropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.navigationPopupSource
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight
import com.metrolist.shared.ui.utils.verticalScrollWithDrag

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> BoxScope.ExpressiveSelectionMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    options: List<T>,
    selectedOption: T?,
    onOptionSelected: (T) -> Unit,
    optionLabel: @Composable (T) -> String,
    anchorOffset: IntOffset = IntOffset.Zero,
    optionIcon: (@Composable (T) -> Unit)? = null,
    optionSupportingText: (@Composable (T) -> String?)? = null,
    optionEnabled: (T) -> Boolean = { true },
) {
    val haptic = rememberMetrolistHaptics()
    Box(
        Modifier.matchParentSize().layout { measurable, constraints ->
            val placeable = measurable.measure(Constraints())
            layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(anchorOffset.x, anchorOffset.y) }
        },
    ) {
        DropdownMenuPopup(expanded = expanded, onDismissRequest = onDismissRequest) {
            DropdownMenuGroup(
                shapes = MenuDefaults.groupShape(0, 1),
                modifier = Modifier.heightIn(max = 480.dp).verticalScrollWithDrag(rememberScrollState()),
            ) {
                options.forEachIndexed { index, option ->
                    val shapes = MenuDefaults.itemShape(index, options.size)
                    val onClick = {
                        haptic.perform(HapticEvent.PRESS)
                        onOptionSelected(option)
                        onDismissRequest()
                    }
                    val text: @Composable () -> Unit = { Text(optionLabel(option)) }
                    val leadingIcon: (@Composable () -> Unit)? = optionIcon?.let { { it(option) } }
                    val supportingText: (@Composable () -> Unit)? = optionSupportingText?.invoke(option)?.let { { Text(it) } }
                    val focusRequester = remember { FocusRequester() }
                    val initiallyFocused = if (selectedOption == null) index == 0 else option == selectedOption
                    if (initiallyFocused) RequestTvFocus(focusRequester)
                    val itemModifier =
                        Modifier.focusRequester(focusRequester).tvFocusHighlight(
                            shape = if (option == selectedOption) shapes.selectedShape else shapes.shape,
                            scale = 1f,
                        )
                    if (selectedOption == null) {
                        DropdownMenuItem(
                            onClick = onClick,
                            text = text,
                            shape = shapes.shape,
                            modifier = itemModifier,
                            leadingIcon = leadingIcon,
                            supportingText = supportingText,
                            enabled = optionEnabled(option),
                        )
                    } else {
                        SelectableDropdownMenuItem(
                            selected = option == selectedOption,
                            onClick = onClick,
                            text = text,
                            shapes = shapes,
                            modifier = itemModifier,
                            leadingIcon = leadingIcon,
                            supportingText = supportingText,
                            enabled = optionEnabled(option),
                            selectedLeadingIcon = {
                                Icon(
                                    MetrolistIcons.Filled.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(MenuDefaults.LeadingIconSize),
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

fun Modifier.trackPressPosition(onPress: (Offset) -> Unit): Modifier =
    composed {
        val currentOnPress by rememberUpdatedState(onPress)
        navigationPopupSource().pointerInput(Unit) {
            awaitEachGesture {
                currentOnPress(awaitFirstDown(requireUnconsumed = false).position)
            }
        }
    }
