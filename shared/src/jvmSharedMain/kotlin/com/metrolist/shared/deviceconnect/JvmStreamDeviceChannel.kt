/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.shared.deviceconnect

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

internal fun createJvmStreamDeviceChannel(
    remoteHost: String?,
    input: InputStream,
    output: OutputStream,
    onClose: () -> Unit = {},
): DeviceChannel =
    LengthPrefixedStreamDeviceChannel(
        remoteHost = remoteHost,
        readBytes = { count ->
            withContext(Dispatchers.IO) {
                val buffer = ByteArray(count)
                var offset = 0
                while (offset < count) {
                    val read = input.read(buffer, offset, count - offset)
                    if (read < 0) {
                        return@withContext if (offset == 0) {
                            null
                        } else {
                            throw IllegalStateException("Unexpected EOF after $offset of $count bytes")
                        }
                    }
                    offset += read
                }
                buffer
            }
        },
        writeBytes = { frame ->
            withContext(Dispatchers.IO) {
                output.write(frame)
                output.flush()
            }
        },
        closeUnderlying = {
            withContext(Dispatchers.IO) {
                runCatching { onClose() }
                runCatching { input.close() }
                runCatching { output.close() }
            }
        },
    )
