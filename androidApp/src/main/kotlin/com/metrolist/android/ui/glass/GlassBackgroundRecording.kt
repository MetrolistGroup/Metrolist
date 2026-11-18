package com.metrolist.android.ui.glass

import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.draw

/** Redirects content to the recording canvas, including deferred software layer replays. */
internal fun ContentDrawScope.drawContentOn(recordingScope: DrawScope) {
    draw(
        density = recordingScope,
        layoutDirection = recordingScope.layoutDirection,
        canvas = recordingScope.drawContext.canvas,
        size = recordingScope.size,
        graphicsLayer = recordingScope.drawContext.graphicsLayer,
    ) {
        this@drawContentOn.drawContent()
    }
}
