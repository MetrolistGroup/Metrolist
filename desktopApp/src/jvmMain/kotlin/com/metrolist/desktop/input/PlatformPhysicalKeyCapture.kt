package com.metrolist.desktop.input

import java.util.ArrayDeque
import java.awt.event.KeyEvent as AwtKeyEvent

internal interface PlatformPhysicalKeyCapture : AutoCloseable {
    val isAvailable: Boolean

    fun setEnabled(enabled: Boolean)

    fun consume(event: AwtKeyEvent): CapturedPhysicalKey?
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
        val nativeTime: Long? = null,
        val nativeKeyCode: Int? = null,
    )

    private val captures = ArrayDeque<Capture>()

    fun record(position: PhysicalKeyPosition?) = record(position, timeSourceMillis())

    @Synchronized
    fun record(
        position: PhysicalKeyPosition?,
        capturedAtMillis: Long,
    ) = addCapture(position, capturedAtMillis, null, null)

    @Synchronized
    fun record(
        position: PhysicalKeyPosition?,
        nativeTime: Long,
        nativeKeyCode: Int,
    ) = addCapture(position, timeSourceMillis(), nativeTime, nativeKeyCode)

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
    fun consume(
        nativeTime: Long,
        nativeKeyCode: Int,
    ): CapturedPhysicalKey? {
        pruneExpired(timeSourceMillis())
        val iterator = captures.iterator()
        while (iterator.hasNext()) {
            val capture = iterator.next()
            if (capture.nativeTime == nativeTime && capture.nativeKeyCode == nativeKeyCode) {
                iterator.remove()
                return CapturedPhysicalKey(capture.position)
            }
        }
        return null
    }

    @Synchronized
    fun clear() = captures.clear()

    private fun addCapture(
        position: PhysicalKeyPosition?,
        capturedAtMillis: Long,
        nativeTime: Long?,
        nativeKeyCode: Int?,
    ) {
        pruneExpired(capturedAtMillis)
        while (captures.size >= MAX_PENDING_CAPTURES) captures.removeFirst()
        captures.addLast(Capture(position, capturedAtMillis, nativeTime, nativeKeyCode))
    }

    private fun pruneExpired(nowMillis: Long) {
        // Native captures are matched by exact identity, so only the pending limit bounds their age.
        while (
            captures.firstOrNull()?.let {
                it.nativeTime == null && nowMillis - it.capturedAtMillis > MAX_CAPTURE_AGE_MILLIS
            } == true
        ) {
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
