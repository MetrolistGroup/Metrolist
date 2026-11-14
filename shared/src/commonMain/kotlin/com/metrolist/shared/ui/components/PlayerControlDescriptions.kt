package com.metrolist.shared.ui.components

import androidx.compose.runtime.Composable
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.menu_repeat_all
import com.metrolist.shared.generated.resources.menu_repeat_off
import com.metrolist.shared.generated.resources.menu_repeat_one
import com.metrolist.shared.generated.resources.repeat_mode_description
import com.metrolist.shared.model.RepeatMode
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun repeatContentDescription(repeatMode: RepeatMode): String =
    stringResource(
        Res.string.repeat_mode_description,
        stringResource(
            when (repeatMode) {
                RepeatMode.OFF -> Res.string.menu_repeat_off
                RepeatMode.ONE -> Res.string.menu_repeat_one
                RepeatMode.ALL -> Res.string.menu_repeat_all
            },
        ),
    )
