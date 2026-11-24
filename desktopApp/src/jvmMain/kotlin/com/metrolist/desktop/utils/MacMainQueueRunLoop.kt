package com.metrolist.desktop.utils

import com.metrolist.shared.utils.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

@Suppress("FunctionName")
private interface CoreFoundation : Library {
    fun CFRunLoopGetMain(): Pointer?

    fun CFStringCreateWithCString(
        allocator: Pointer?,
        string: String,
        encoding: Int,
    ): Pointer?

    fun CFRunLoopAddCommonMode(
        runLoop: Pointer?,
        mode: Pointer?,
    )
}

private const val CF_STRING_ENCODING_UTF8 = 0x08000100

/**
 * Wry runs every WebView call with a blocking dispatch to the main GCD queue from the AWT EDT. While the AppKit
 * thread waits on the EDT (CAccessibility through LWCToolkit.invokeAndWait), its nested loop runs only in
 * AWTRunLoopMode, and CoreFoundation drains the main queue only in common modes, so both threads wait forever.
 * Making AWTRunLoopMode a common mode lets that nested loop run the queued WebView work.
 */
fun allowMainQueueInAwtRunLoop() {
    runCatching {
        val cf = Native.load("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", CoreFoundation::class.java)
        val mode =
            checkNotNull(cf.CFStringCreateWithCString(null, "AWTRunLoopMode", CF_STRING_ENCODING_UTF8)) {
                "CFStringCreateWithCString returned null"
            }
        cf.CFRunLoopAddCommonMode(cf.CFRunLoopGetMain(), mode)
    }.onFailure { Logger.w("MacMainQueueRunLoop", "Could not add AWTRunLoopMode to common modes", it) }
}
