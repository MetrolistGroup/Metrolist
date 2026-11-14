package com.metrolist.shared.ui.navigation

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import com.metrolist.shared.utils.isDesktop

val LocalUseDesktopUi = staticCompositionLocalOf { isDesktop }

val DesktopContentMaxWidth = 1200.dp

fun AdaptiveLayout.withPointerInput(hasPointer: Boolean): AdaptiveLayout =
    if (hasPointer && formFactor == AdaptiveFormFactor.TOUCH && widthBreakpoint >= AdaptiveWidthBreakpoint.EXPANDED) {
        copy(formFactor = AdaptiveFormFactor.DESKTOP)
    } else {
        this
    }
