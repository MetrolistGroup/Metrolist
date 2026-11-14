package com.metrolist.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal class AccountMenuDismissal {
    var visible by mutableStateOf(true)
        private set
    private var action: (() -> Unit)? = null

    fun dismiss(afterDismiss: () -> Unit = {}) {
        if (visible) {
            action = afterDismiss
            visible = false
        }
    }

    fun finish(onDismiss: () -> Unit) {
        val pending = action
        action = null
        onDismiss()
        pending?.invoke()
    }
}

@Composable
internal fun AccountMenuContainer(
    modifier: Modifier,
    shape: Shape,
    color: Color,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .popupMotion()
            .shadow(6.dp, shape)
            .background(color, shape)
            .semantics { isTraversalGroup = true }
            .pointerInput(Unit) { detectTapGestures {} },
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColorFor(color), content = content)
    }
}
