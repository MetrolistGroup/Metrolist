package com.metrolist.shared.playback.ffmpeg

import java.io.Closeable
import java.io.IOException

abstract class FrameGrabber : Closeable {
    enum class ImageMode {
        COLOR,
        GRAY,
        RAW,
    }

    enum class SampleMode {
        SHORT,
        FLOAT,
        RAW,
    }

    open var videoStream = -1
    open var audioStream = -1
    open var videoDisposition = 0
    open var audioDisposition = 0
    open var format: String? = null
    open var videoCodecName: String? = null
    open var audioCodecName: String? = null
    open var imageWidth = 0
    open var imageHeight = 0
    open var audioChannels = 0
    open var imageMode = ImageMode.COLOR
    open var pixelFormat = -1
    open var videoCodec = 0
    open var videoBitrate = 0
    open var imageScalingFlags = 0
    open var aspectRatio = 0.0
    open var frameRate = 0.0
    open var sampleMode = SampleMode.SHORT
    open var sampleFormat = -1
    open var audioCodec = 0
    open var audioBitrate = 0
    open var sampleRate = 0
    open var timeout = 10000
    open var options: MutableMap<String, String> = HashMap()
    open var videoOptions: MutableMap<String, String> = HashMap()
    open var audioOptions: MutableMap<String, String> = HashMap()
    open var metadata: MutableMap<String, String> = HashMap()
    open var frameNumber = 0
    open var timestamp = 0L
    open var maxDelay = -1
    open var startTime = 0L

    open fun getLengthInTime(): Long = 0

    abstract fun hasVideo(): Boolean

    abstract fun hasAudio(): Boolean

    open class Exception : IOException {
        constructor(message: String) : super(message)
        constructor(message: String, cause: Throwable) : super(message, cause)
    }

    @Throws(Exception::class)
    abstract fun start()

    @Throws(Exception::class)
    abstract fun stop()

    @Throws(Exception::class)
    override fun close() {
        stop()
        release()
    }

    @Throws(Exception::class)
    abstract fun grab(): Frame?

    @Throws(Exception::class)
    open fun grabFrame(): Frame? = grab()

    @Throws(Exception::class)
    abstract fun release()

    @Throws(Exception::class)
    open fun restart() {
        stop()
        start()
    }
}
