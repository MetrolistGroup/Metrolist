package com.metrolist.desktop.input

import java.util.ArrayDeque

internal interface PlatformPhysicalKeyCapture : AutoCloseable {
    val isAvailable: Boolean

    fun setEnabled(enabled: Boolean)

    fun consume(awtEventWhen: Long): CapturedPhysicalKey?
}

internal data class CapturedPhysicalKey(
    val position: PhysicalKeyPosition?,
)

internal class OrderedPhysicalKeyCaptureBuffer(
    private val timeSourceMillis: () -> Long = System::currentTimeMillis,
) {
    private data class Capture(
        val position: PhysicalKeyPosition?,
        val capturedAtMillis: Long,
    )

    private val captures = ArrayDeque<Capture>()

    @Synchronized
    fun record(position: PhysicalKeyPosition?) {
        val now = timeSourceMillis()
        pruneExpired(now)
        while (captures.size >= MAX_PENDING_CAPTURES) captures.removeFirst()
        captures.addLast(Capture(position, now))
    }

    @Synchronized
    fun consume(awtEventWhen: Long): CapturedPhysicalKey? {
        pruneExpired(timeSourceMillis())
        while (
            captures.firstOrNull()?.capturedAtMillis?.let {
                it < awtEventWhen - MAX_EVENT_ALIGNMENT_MILLIS
            } == true
        ) {
            captures.removeFirst()
        }
        val capture = captures.firstOrNull() ?: return null
        if (capture.capturedAtMillis > awtEventWhen + MAX_EVENT_ALIGNMENT_MILLIS) return null
        return CapturedPhysicalKey(captures.removeFirst().position)
    }

    @Synchronized
    fun clear() = captures.clear()

    private fun pruneExpired(nowMillis: Long) {
        while (captures.firstOrNull()?.let { nowMillis - it.capturedAtMillis > MAX_CAPTURE_AGE_MILLIS } == true) {
            captures.removeFirst()
        }
    }

    private companion object {
        const val MAX_PENDING_CAPTURES = 128
        const val MAX_CAPTURE_AGE_MILLIS = 5_000L
        const val MAX_EVENT_ALIGNMENT_MILLIS = 100L
    }
}

internal fun macOsPhysicalPositionForKeyCode(keyCode: Int): PhysicalKeyPosition? =
    when (keyCode) {
        50 -> PhysicalKeyPosition.BACKQUOTE
        27 -> PhysicalKeyPosition.MINUS
        24 -> PhysicalKeyPosition.EQUALS
        69 -> PhysicalKeyPosition.NUMPAD_PLUS
        43 -> PhysicalKeyPosition.COMMA
        44 -> PhysicalKeyPosition.SLASH
        else -> null
    }

internal fun linuxPhysicalPositionForX11KeyCode(keyCode: Int): PhysicalKeyPosition? =
    when (keyCode - X11_KEYCODE_OFFSET) {
        LINUX_KEY_GRAVE -> PhysicalKeyPosition.BACKQUOTE
        LINUX_KEY_MINUS -> PhysicalKeyPosition.MINUS
        LINUX_KEY_EQUAL -> PhysicalKeyPosition.EQUALS
        LINUX_KEY_KP_PLUS -> PhysicalKeyPosition.NUMPAD_PLUS
        LINUX_KEY_COMMA -> PhysicalKeyPosition.COMMA
        LINUX_KEY_SLASH -> PhysicalKeyPosition.SLASH
        else -> null
    }

private const val X11_KEYCODE_OFFSET = 8
private const val LINUX_KEY_GRAVE = 41
private const val LINUX_KEY_MINUS = 12
private const val LINUX_KEY_EQUAL = 13
private const val LINUX_KEY_KP_PLUS = 78
private const val LINUX_KEY_COMMA = 51
private const val LINUX_KEY_SLASH = 53
