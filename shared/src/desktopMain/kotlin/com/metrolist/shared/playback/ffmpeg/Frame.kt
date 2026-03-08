package com.metrolist.shared.playback.ffmpeg

import org.bytedeco.javacpp.Pointer
import java.nio.Buffer
import java.nio.ByteBuffer

class Frame : AutoCloseable {
    var keyFrame: Boolean = false
    var pictType: Char = '\u0000'

    companion object {
        const val DEPTH_UBYTE = 8
    }

    enum class Type {
        VIDEO,
        AUDIO,
        DATA,
        SUBTITLE,
        ATTACHMENT,
    }

    var imageWidth: Int = 0
    var imageHeight: Int = 0
    var imageDepth: Int = 0
    var imageChannels: Int = 0
    var imageStride: Int = 0
    var image: Array<Buffer?>? = null

    var sampleRate: Int = 0
    var audioChannels: Int = 0
    var samples: Array<Buffer?>? = null

    var data: ByteBuffer? = null
    var streamIndex: Int = -1
    var type: Type? = null
    var opaque: Any? = null
    var timestamp: Long = 0

    override fun close() {
        if (opaque is Array<*>) {
            val array = opaque as Array<*>
            for (pointer in array) {
                if (pointer is Pointer) pointer.releaseReference()
            }
            opaque = null
        }
    }
}
