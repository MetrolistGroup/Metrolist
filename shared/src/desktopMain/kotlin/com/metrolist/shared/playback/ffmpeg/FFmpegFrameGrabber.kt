@file:Suppress(
    "ktlint:standard:backing-property-naming",
    "ktlint:standard:no-wildcard-imports",
    "ktlint:standard:property-naming",
)

package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.utils.Logger
import org.bytedeco.ffmpeg.avcodec.*
import org.bytedeco.ffmpeg.avformat.*
import org.bytedeco.ffmpeg.avutil.*
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swresample.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.ffmpeg.swresample.*
import org.bytedeco.ffmpeg.swscale.*
import org.bytedeco.javacpp.*
import java.io.*
import java.net.URL
import java.nio.*
import java.util.*
import kotlin.math.abs
import kotlin.math.roundToLong

private const val MAX_SOFTWARE_VIDEO_DECODER_THREADS = 4

private fun ffmpegErrorDescription(code: Int): String =
    ByteArray(128).also { av_strerror(code, it, it.size.toLong()) }.decodeToString().substringBefore('\u0000')

internal fun softwareVideoDecoderThreadCount(availableProcessors: Int): Int =
    availableProcessors.coerceIn(1, MAX_SOFTWARE_VIDEO_DECODER_THREADS)

internal fun canDirectCopyPlanar(
    sourceWidth: Int,
    sourceHeight: Int,
    destinationWidth: Int,
    destinationHeight: Int,
): Boolean =
    sourceWidth > 0 && sourceHeight > 0 &&
        sourceWidth == destinationWidth && sourceHeight == destinationHeight

class FFmpegFrameGrabber : FrameGrabber {
    companion object {
        class Exception : FrameGrabber.Exception {
            constructor(message: String) : super("$message (For more details, make sure FFmpegLogCallback.set() has been called.)")
            constructor(message: String, cause: Throwable) : super(message, cause)
        }

        @Volatile
        private var loadingException: Exception? = null

        @JvmStatic
        @Throws(Exception::class)
        fun tryLoad() {
            loadingException?.let { throw it }
            try {
                Loader.load(org.bytedeco.ffmpeg.global.avutil::class.java)
                Loader.load(org.bytedeco.ffmpeg.global.swresample::class.java)
                Loader.load(org.bytedeco.ffmpeg.global.avcodec::class.java)
                Loader.load(org.bytedeco.ffmpeg.global.avformat::class.java)
                Loader.load(org.bytedeco.ffmpeg.global.swscale::class.java)
                FFmpegLogCallback.set()

                av_jni_set_java_vm(Loader.getJavaVM(), null)
                avformat_network_init()
            } catch (t: Throwable) {
                val ex = t as? Exception ?: Exception("Failed to load ${FFmpegFrameGrabber::class.java}", t)
                loadingException = ex
                throw ex
            }
        }

        init {
            try {
                tryLoad()
            } catch (ex: java.lang.Exception) {
            }
        }

        private val inputStreams = Collections.synchronizedMap(HashMap<Long, InputStream>())

        private val readCallback by lazy {
            object : Read_packet_Pointer_BytePointer_int() {
                override fun call(
                    opaque: Pointer?,
                    buf: BytePointer?,
                    buf_size: Int,
                ): Int {
                    return try {
                        val b = ByteArray(buf_size)
                        val is_ = inputStreams[opaque?.address()] ?: return -1
                        val size = is_.read(b, 0, buf_size)
                        if (size < 0) {
                            AVERROR_EOF()
                        } else {
                            buf?.put(b, 0, size)
                            size
                        }
                    } catch (t: Throwable) {
                        if (t !is InterruptedIOException &&
                            t !is kotlin.coroutines.cancellation.CancellationException
                        ) {
                            Logger.w("FFmpegGrabber", "Error on InputStream.read(): $t")
                        }
                        -1
                    }
                }
            }.retainReference<Read_packet_Pointer_BytePointer_int>()
        }

        private val seekCallback by lazy {
            object : Seek_Pointer_long_int() {
                override fun call(
                    opaque: Pointer?,
                    offset: Long,
                    whence: Int,
                ): Long {
                    return try {
                        val is_ = inputStreams[opaque?.address()] ?: return -1
                        if (is_ is SeekableMediaInput) {
                            if (whence == AVSEEK_SIZE) return is_.mediaSize ?: -1L
                            val targetPosition =
                                when (whence) {
                                    0 -> offset
                                    1 -> is_.mediaPosition + offset
                                    2 -> (is_.mediaSize ?: return -1L) + offset
                                    else -> return -1L
                                }
                            return is_.seekToMediaPosition(targetPosition)
                        }
                        var size = 0L
                        var targetOffset = offset
                        when (whence) {
                            0 -> {
                                is_.reset()
                            }

                            1 -> {}

                            2 -> {
                                is_.reset()
                                while (true) {
                                    val n = is_.skip(Long.MAX_VALUE)
                                    if (n == 0L) break
                                    size += n
                                }
                                targetOffset += size
                                is_.reset()
                            }

                            AVSEEK_SIZE -> {
                                var remaining = 0L
                                while (true) {
                                    val n = is_.skip(Long.MAX_VALUE)
                                    if (n == 0L) break
                                    remaining += n
                                }
                                is_.reset()
                                while (true) {
                                    val n = is_.skip(Long.MAX_VALUE)
                                    if (n == 0L) break
                                    size += n
                                }
                                targetOffset = size - remaining
                                is_.reset()
                            }

                            else -> {
                                return -1
                            }
                        }
                        var remaining = targetOffset
                        while (remaining > 0) {
                            val skipped = is_.skip(remaining)
                            if (skipped == 0L) break
                            remaining -= skipped
                        }
                        if (whence == AVSEEK_SIZE) size else 0L
                    } catch (t: Throwable) {
                        Logger.w("FFmpegGrabber", "Error on InputStream.reset() or skip(): $t")
                        -1L
                    }
                }
            }.retainReference<Seek_Pointer_long_int>()
        }
    }

    private var sourceInputStream: InputStream? = null
    private var inputStreamAddress: Long? = null
    internal val mediaInputStream: InputStream?
        get() = sourceInputStream
    private var closeInputStream: Boolean = false
    private var maximumSize: Int = 0
    private var avio: AVIOContext? = null
    private var filename: String? = null
    private var oc: AVFormatContext? = null
    private var video_st: AVStream? = null
    private var audio_st: AVStream? = null
    private var video_c: AVCodecContext? = null
    private var audio_c: AVCodecContext? = null
    private var picture: AVFrame? = null
    private var picture_rgb: AVFrame? = null
    private var image_ptr: kotlin.Array<BytePointer?>? = null
    private var image_buf: kotlin.Array<Buffer?>? = null
    private var samples_frame: AVFrame? = null
    private var samples_ptr: kotlin.Array<BytePointer?>? = null
    private var samples_buf: kotlin.Array<Buffer?>? = null
    private var samples_ptr_out: kotlin.Array<BytePointer?>? = null
    private var samples_buf_out: kotlin.Array<Buffer?>? = null
    private var plane_ptr: PointerPointer<Pointer>? = null
    private var plane_ptr2: PointerPointer<Pointer>? = null
    private var hardware_picture: AVFrame? = null
    private var hardwarePictureValid = false
    private var custom_target_picture: AVFrame? = null
    private var custom_target_ptr: CustomAddressPointer? = null
    private var pkt: AVPacket? = null
    private var img_convert_ctx: SwsContext? = null
    private var samples_convert_ctx: SwrContext? = null
    private var samples_channels: Int = 0
    private var samples_format: Int = 0
    private var samples_rate: Int = 0
    private var frameGrabbed: Boolean = false
    private var frame: Frame? = null
    private var streams: IntArray? = null
    private var default_layout: AVChannelLayout? = null

    @Volatile
    internal var started = false
        private set

    protected var _frameNumber = 0
    protected var _timestamp = 0L

    constructor(url: URL) : this(url.toString())
    constructor(file: File) : this(file.absolutePath)
    constructor(filename: String) {
        this.filename = filename
        super.pixelFormat = AV_PIX_FMT_NONE
        super.sampleFormat = AV_SAMPLE_FMT_NONE
    }

    constructor(inputStream: InputStream) : this(inputStream, Int.MAX_VALUE - 8)
    constructor(inputStream: InputStream, maximumSize: Int) {
        this.sourceInputStream = inputStream
        this.closeInputStream = true
        super.pixelFormat = AV_PIX_FMT_NONE
        super.sampleFormat = AV_SAMPLE_FMT_NONE
        this.maximumSize = maximumSize
    }

    var isHardwareAccelerated: Boolean = false
        private set
    var hardwareDeviceName: String? = null
        private set
    private var hw_device_ctx: AVBufferRef? = null
    private var get_format_callback: AVCodecContext.Get_format_AVCodecContext_IntPointer? = null

    override fun release() {
        synchronized(org.bytedeco.ffmpeg.global.avcodec::class.java) {
            releaseUnsafe()
        }
    }

    @Synchronized
    fun releaseUnsafe() {
        started = false
        hardwarePictureValid = false

        plane_ptr?.releaseReference()
        plane_ptr2?.releaseReference()
        plane_ptr = null
        plane_ptr2 = null

        pkt?.let {
            if (it.stream_index() != -1) {
                av_packet_unref(it)
            }
            it.releaseReference()
        }
        pkt = null

        default_layout?.releaseReference()
        default_layout = null

        image_ptr?.let {
            for (ptr in it) {
                if (imageMode != ImageMode.RAW) {
                    av_free(ptr)
                }
            }
        }
        image_ptr = null
        image_buf = null

        picture_rgb?.let { av_frame_free(it) }
        picture_rgb = null

        picture?.let { av_frame_free(it) }
        picture = null

        hardware_picture?.let { av_frame_free(it) }
        hardware_picture = null

        custom_target_picture?.let { av_frame_free(it) }
        custom_target_picture = null
        custom_target_ptr = null

        video_c?.let { avcodec_free_context(it) }
        video_c = null

        // Codec shutdown joins its native workers, which may still call get_format.
        get_format_callback?.releaseReference()
        get_format_callback = null
        hw_device_ctx?.let {
            av_buffer_unref(it)
            it.releaseReference()
        }
        hw_device_ctx = null
        isHardwareAccelerated = false
        hardwareDeviceName = null

        samples_frame?.let { av_frame_free(it) }
        samples_frame = null

        audio_c?.let { avcodec_free_context(it) }
        audio_c = null

        img_convert_ctx?.let { sws_freeContext(it) }
        img_convert_ctx = null

        planar_convert_ctx?.let { sws_freeContext(it) }
        planar_convert_ctx = null
        planar_src_data?.releaseReference()
        planar_src_data = null
        planar_src_linesize?.releaseReference()
        planar_src_linesize = null
        planar_dst_data?.releaseReference()
        planar_dst_data = null
        planar_dst_linesize?.releaseReference()
        planar_dst_linesize = null

        samples_ptr_out?.let {
            for (ptr in it) {
                av_free(ptr?.position(0L))
            }
        }
        samples_ptr_out = null
        samples_buf_out = null

        samples_convert_ctx?.let {
            swr_free(it)
            it.releaseReference()
        }
        samples_convert_ctx = null

        frameGrabbed = false
        frame = null
        _timestamp = 0L
        _frameNumber = 0

        // Demuxer shutdown can still access custom IO and its callbacks.
        oc?.let {
            if (!it.isNull) {
                avformat_close_input(it)
            }
        }
        oc = null

        sourceInputStream?.let { is_ ->
            try {
                if (closeInputStream) is_.close()
            } catch (ex: IOException) {
                Logger.w("FFmpegGrabber", "Error on InputStream.close(): $ex")
            } finally {
                inputStreamAddress?.let { inputStreams.remove(it) }
                inputStreamAddress = null
            }
        }

        avio?.let { a ->
            if (a.buffer() != null) {
                av_free(a.buffer())
                a.buffer(null)
            }
            av_free(a)
        }
        avio = null
    }

    override fun hasVideo(): Boolean = video_st != null

    override fun hasAudio(): Boolean = audio_st != null

    override var format: String?
        get() =
            oc
                ?.takeUnless { it.isNull }
                ?.iformat()
                ?.name()
                ?.string ?: super.format
        set(value) {
            super.format = value
        }

    override var imageWidth: Int
        get() =
            if (super.imageWidth > 0 ||
                video_c == null
            ) {
                super.imageWidth
            } else {
                video_c?.takeUnless { it.isNull }?.width() ?: super.imageWidth
            }
        set(value) {
            super.imageWidth = value
        }

    override var imageHeight: Int
        get() =
            if (super.imageHeight > 0 ||
                video_c == null
            ) {
                super.imageHeight
            } else {
                video_c?.takeUnless { it.isNull }?.height() ?: super.imageHeight
            }
        set(value) {
            super.imageHeight = value
        }

    override var audioChannels: Int
        get() =
            if (super.audioChannels > 0 ||
                audio_c == null
            ) {
                super.audioChannels
            } else {
                audio_c?.takeUnless { it.isNull }?.ch_layout()?.nb_channels() ?: super.audioChannels
            }
        set(value) {
            super.audioChannels = value
        }

    override var pixelFormat: Int
        get() {
            return if (imageMode == ImageMode.COLOR || imageMode == ImageMode.GRAY) {
                if (super.pixelFormat == AV_PIX_FMT_NONE) {
                    if (imageMode == ImageMode.COLOR) AV_PIX_FMT_BGR24 else AV_PIX_FMT_GRAY8
                } else {
                    super.pixelFormat
                }
            } else if (video_c != null && !video_c!!.isNull) {
                video_c!!.pix_fmt()
            } else {
                super.pixelFormat
            }
        }
        set(value) {
            super.pixelFormat = value
        }

    override var videoCodec: Int
        get() = video_c?.takeUnless { it.isNull }?.codec_id() ?: super.videoCodec
        set(value) {
            super.videoCodec = value
        }

    override var videoCodecName: String?
        get() =
            video_c
                ?.takeUnless { it.isNull }
                ?.codec()
                ?.name()
                ?.string ?: super.videoCodecName
        set(value) {
            super.videoCodecName = value
        }

    override var videoBitrate: Int
        get() = video_c?.takeUnless { it.isNull }?.bit_rate()?.toInt() ?: super.videoBitrate
        set(value) {
            super.videoBitrate = value
        }

    override var aspectRatio: Double
        get() {
            return if (video_st == null || oc == null || oc!!.isNull) {
                super.aspectRatio
            } else {
                val r = av_guess_sample_aspect_ratio(oc, video_st, picture)
                val a = r.num().toDouble() / r.den()
                if (a == 0.0) 1.0 else a
            }
        }
        set(value) {
            super.aspectRatio = value
        }

    override var frameRate: Double
        get() = getVideoFrameRate()
        set(value) {
            super.frameRate = value
        }

    fun getVideoFrameRate(): Double {
        if (video_st == null || video_st!!.isNull) return super.frameRate
        var r = video_st!!.avg_frame_rate()
        if (r.num() == 0 && r.den() == 0) {
            r = video_st!!.r_frame_rate()
        }
        return r.num().toDouble() / r.den()
    }

    override var audioCodec: Int
        get() = audio_c?.takeUnless { it.isNull }?.codec_id() ?: super.audioCodec
        set(value) {
            super.audioCodec = value
        }

    override var audioCodecName: String?
        get() =
            audio_c
                ?.takeUnless { it.isNull }
                ?.codec()
                ?.name()
                ?.string ?: super.audioCodecName
        set(value) {
            super.audioCodecName = value
        }

    override var audioBitrate: Int
        get() = audio_c?.takeUnless { it.isNull }?.bit_rate()?.toInt() ?: super.audioBitrate
        set(value) {
            super.audioBitrate = value
        }

    val formatName: String?
        get() =
            oc
                ?.takeUnless { it.isNull }
                ?.iformat()
                ?.name()
                ?.string

    val rawPixelFormatName: String?
        get() =
            video_c?.takeUnless { it.isNull }?.let {
                val fmt = it.pix_fmt()
                if (fmt != AV_PIX_FMT_NONE) av_get_pix_fmt_name(fmt)?.string else null
            }

    val colorSpaceName: String?
        get() =
            video_c?.takeUnless { it.isNull }?.let {
                when (it.colorspace()) {
                    1 -> "bt709"
                    2 -> "unspecified"
                    4 -> "fcc"
                    5 -> "bt470bg"
                    6 -> "smpte170m"
                    7 -> "smpte240m"
                    9 -> "bt2020nc"
                    10 -> "bt2020c"
                    else -> null
                }
            }

    override var sampleFormat: Int
        get() {
            return if (sampleMode == SampleMode.SHORT || sampleMode == SampleMode.FLOAT) {
                if (super.sampleFormat == AV_SAMPLE_FMT_NONE) {
                    if (sampleMode == SampleMode.SHORT) AV_SAMPLE_FMT_S16 else AV_SAMPLE_FMT_FLT
                } else {
                    super.sampleFormat
                }
            } else if (audio_c != null && !audio_c!!.isNull) {
                audio_c!!.sample_fmt()
            } else {
                super.sampleFormat
            }
        }
        set(value) {
            super.sampleFormat = value
        }

    override var sampleRate: Int
        get() = if (super.sampleRate > 0 || audio_c == null || audio_c!!.isNull) super.sampleRate else audio_c!!.sample_rate()
        set(value) {
            super.sampleRate = value
        }

    override fun getLengthInTime(): Long = (oc?.takeUnless { it.isNull }?.duration() ?: 0L) * 1000000L / AV_TIME_BASE

    override fun start() {
        start(true)
    }

    fun start(findStreamInfo: Boolean) {
        synchronized(org.bytedeco.ffmpeg.global.avcodec::class.java) {
            startUnsafe(findStreamInfo)
        }
    }

    fun startUnsafe() {
        startUnsafe(true)
    }

    @Synchronized
    fun startUnsafe(findStreamInfo: Boolean) {
        // Failed static loading leaves native classes unusable; retain the original failure.
        loadingException?.let { throw it }
        PointerScope().use { _ ->
            if (oc != null && !oc!!.isNull) {
                throw Exception("start() has already been called: Call stop() before calling start() again.")
            }

            img_convert_ctx = null
            oc = AVFormatContext(null)
            video_c = null
            audio_c = null
            plane_ptr = PointerPointer<Pointer>(AVFrame.AV_NUM_DATA_POINTERS.toLong()).retainReference()
            plane_ptr2 = PointerPointer<Pointer>(AVFrame.AV_NUM_DATA_POINTERS.toLong()).retainReference()
            pkt = AVPacket().retainReference()
            frameGrabbed = false
            frame = Frame()
            _timestamp = 0L
            _frameNumber = 0
            default_layout = AVChannelLayout().retainReference()

            pkt!!.stream_index(-1)

            var f: AVInputFormat? = null
            if (!super.format.isNullOrEmpty()) {
                f = av_find_input_format(super.format)
                if (f == null) throw Exception("av_find_input_format() error: Could not find input format \"${super.format}\".")
            }

            val options = AVDictionary(null)
            val isRawDemuxer = !super.format.isNullOrEmpty() && (super.format == "rawvideo" || super.format == "image2")
            if (super.frameRate > 0) {
                val r = av_d2q(super.frameRate, 1001000)
                av_dict_set(options, "framerate", "${r.num()}/${r.den()}", 0)
            }
            if (super.pixelFormat >= 0 && isRawDemuxer) {
                av_dict_set(options, "pixel_format", av_get_pix_fmt_name(super.pixelFormat).string, 0)
            } else if (imageMode != ImageMode.RAW && videoStream >= 0 && isRawDemuxer) {
                av_dict_set(options, "pixel_format", if (imageMode == ImageMode.COLOR) "bgr24" else "gray8", 0)
            }
            if (super.imageWidth > 0 &&
                super.imageHeight > 0 &&
                isRawDemuxer
            ) {
                av_dict_set(options, "video_size", "${super.imageWidth}x${super.imageHeight}", 0)
            }
            if (super.sampleRate > 0 && !super.format.isNullOrEmpty() &&
                super.format!!.startsWith("s16")
            ) {
                av_dict_set(options, "sample_rate", "${super.sampleRate}", 0)
            }
            if (super.audioChannels > 0 && !super.format.isNullOrEmpty() &&
                super.format!!.startsWith("s16")
            ) {
                av_dict_set(options, "channels", "${super.audioChannels}", 0)
            }
            for ((key, value) in this.options) {
                if (key != "hwaccel") {
                    av_dict_set(options, key, value, 0)
                }
            }

            sourceInputStream?.let { is_ ->
                val bis = if (is_ is SeekableMediaInput || is_.markSupported()) is_ else BufferedInputStream(is_)
                if (bis.markSupported()) bis.mark(maximumSize)
                sourceInputStream = bis
                oc = avformat_alloc_context()
                avio =
                    avio_alloc_context(
                        BytePointer(av_malloc(4096L)),
                        4096,
                        0,
                        oc,
                        readCallback,
                        null,
                        if (maximumSize >
                            0
                        ) {
                            seekCallback
                        } else {
                            null
                        },
                    )
                oc!!.pb(avio)
                filename = bis.toString()
                inputStreamAddress = oc!!.address()
                inputStreams[inputStreamAddress!!] = bis
            }
            val diagnosticFilename =
                filename?.let {
                    if (it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)) {
                        "<remote media>"
                    } else {
                        it
                    }
                }

            val retryWithoutPixelFormat = sourceInputStream == null && av_dict_get(options, "pixel_format", null, 0) != null
            var ret: Int
            try {
                ret = avformat_open_input(oc, filename, f, options)
                // A failed open frees the format context, so a retry would lose custom IO.
                if (ret < 0 && retryWithoutPixelFormat) {
                    av_dict_set(options, "pixel_format", null, 0)
                    ret = avformat_open_input(oc, filename, f, options)
                }
                if (ret < 0) {
                    throw Exception(
                        "avformat_open_input() error $ret: ${ffmpegErrorDescription(ret)}. Could not open input \"$diagnosticFilename\".",
                    )
                }
                FFmpegLogCallback.logRejectedOptions(options, "avformat_open_input")
            } finally {
                av_dict_free(options)
            }

            oc!!.max_delay(maxDelay)

            if (findStreamInfo) {
                ret = avformat_find_stream_info(oc, null as PointerPointer<*>?)
                if (ret < 0) throw Exception("avformat_find_stream_info() error $ret: ${ffmpegErrorDescription(ret)}.")
            }

            if (av_log_get_level() >= AV_LOG_INFO) av_dump_format(oc, 0, diagnosticFilename, 0)

            val nb_streams = oc!!.nb_streams()
            for (i in 0 until nb_streams) {
                val st = oc!!.streams(i)
                val par = st.codecpar()
                if (videoStream == -1 && par.codec_type() == AVMEDIA_TYPE_VIDEO && st.disposition() == videoDisposition) {
                    videoStream = i
                } else if (audioStream == -1 && par.codec_type() == AVMEDIA_TYPE_AUDIO && st.disposition() == audioDisposition) {
                    audioStream = i
                }
            }

            video_st = null
            audio_st = null
            var video_par: AVCodecParameters? = null
            var audio_par: AVCodecParameters? = null
            streams = IntArray(nb_streams)
            for (i in 0 until nb_streams) {
                val st = oc!!.streams(i)
                val par = st.codecpar()
                streams!![i] = par.codec_type()
                if (video_st == null && par.codec_type() == AVMEDIA_TYPE_VIDEO && par.codec_id() != AV_CODEC_ID_NONE &&
                    (videoStream == -1 || videoStream == i)
                ) {
                    video_st = st
                    video_par = par
                    videoStream = i
                } else if (audio_st == null && par.codec_type() == AVMEDIA_TYPE_AUDIO && par.codec_id() != AV_CODEC_ID_NONE &&
                    (audioStream == -1 || audioStream == i)
                ) {
                    audio_st = st
                    audio_par = par
                    audioStream = i
                }
            }

            if (video_st == null && audio_st == null) {
                throw Exception(
                    "Did not find a video or audio stream inside \"$diagnosticFilename\" for videoStream == $videoStream and audioStream == $audioStream.",
                )
            }

            video_st?.let { st ->
                var codec = if (videoCodecName != null) avcodec_find_decoder_by_name(videoCodecName) else null
                if (codec == null) codec = avcodec_find_decoder(video_par!!.codec_id())
                if (codec ==
                    null
                ) {
                    throw Exception(
                        "avcodec_find_decoder() error: Unsupported video format or codec not found: ${video_par!!.codec_id()}.",
                    )
                }

                video_c = avcodec_alloc_context3(codec)
                if (video_c == null) throw Exception("avcodec_alloc_context3() error: Could not allocate video decoding context.")

                if (avcodec_parameters_to_context(video_c, st.codecpar()) < 0) {
                    releaseUnsafe()
                    throw Exception("avcodec_parameters_to_context() error: Could not copy the video stream parameters.")
                }

                val vOptions = AVDictionary(null)
                for ((key, value) in videoOptions) av_dict_set(vOptions, key, value, 0)

                val hwaccelOpt = this.options["hwaccel"]
                var hwAccelConfigured = false
                if (!hwaccelOpt.isNullOrEmpty() && hwaccelOpt != "none" && hwaccelOpt != "0") {
                    val targetHwNames =
                        when (hwaccelOpt) {
                            "auto" -> {
                                val osName = System.getProperty("os.name")?.lowercase() ?: ""
                                when {
                                    osName.contains("mac") -> listOf("videotoolbox")
                                    osName.contains("win") -> listOf("d3d11va", "dxva2")
                                    else -> listOf("vaapi", "cuda", "vdpau")
                                }
                            }

                            else -> {
                                listOf(hwaccelOpt)
                            }
                        }

                    for (hwName in targetHwNames) {
                        val hwType = av_hwdevice_find_type_by_name(hwName)
                        if (hwType == AV_HWDEVICE_TYPE_NONE) continue
                        val hwCtx = AVBufferRef()
                        if (av_hwdevice_ctx_create(hwCtx, hwType, null as String?, null as AVDictionary?, 0) >= 0) {
                            val refForCodec = av_buffer_ref(hwCtx)
                            video_c!!.hw_device_ctx(refForCodec)
                            hw_device_ctx = hwCtx.retainReference()
                            hardwareDeviceName = hwName

                            val targetHwPixFmt = getHwPixelFormat(hwType)
                            if (targetHwPixFmt != AV_PIX_FMT_NONE) {
                                val cb =
                                    object : AVCodecContext.Get_format_AVCodecContext_IntPointer() {
                                        override fun call(
                                            ctx: AVCodecContext?,
                                            fmt: IntPointer?,
                                        ): Int {
                                            return try {
                                                if (fmt != null) {
                                                    val safeFmt = fmt.capacity(64)
                                                    var i = 0
                                                    while (i < 64) {
                                                        val p = safeFmt.get(i.toLong())
                                                        if (p == AV_PIX_FMT_NONE) break
                                                        if (p == targetHwPixFmt) {
                                                            isHardwareAccelerated = true
                                                            return p
                                                        }
                                                        i++
                                                    }
                                                    isHardwareAccelerated = false
                                                    safeFmt.get(0L)
                                                } else {
                                                    AV_PIX_FMT_NONE
                                                }
                                            } catch (_: Throwable) {
                                                isHardwareAccelerated = false
                                                fmt?.get(0L) ?: AV_PIX_FMT_NONE
                                            }
                                        }
                                    }.retainReference<AVCodecContext.Get_format_AVCodecContext_IntPointer>()
                                get_format_callback = cb
                                video_c!!.get_format(cb)
                            }
                            hwAccelConfigured = true
                            break
                        }
                    }
                }

                if (hwAccelConfigured) {
                    // Hardware accelerated decoders (e.g. VideoToolbox) require single-threaded decoding
                    video_c!!.thread_count(1)
                    video_c!!.thread_type(0)
                } else {
                    video_c!!.thread_count(softwareVideoDecoderThreadCount(Runtime.getRuntime().availableProcessors()))
                    video_c!!.thread_type(AVCodecContext.FF_THREAD_FRAME or AVCodecContext.FF_THREAD_SLICE)
                }

                var openResult = avcodec_open2(video_c, codec, vOptions)
                if (openResult < 0 && hwAccelConfigured) {
                    hw_device_ctx?.let {
                        av_buffer_unref(it)
                        it.releaseReference()
                    }
                    hw_device_ctx = null
                    get_format_callback?.releaseReference()
                    get_format_callback = null
                    isHardwareAccelerated = false
                    hardwareDeviceName = null
                    video_c!!.hw_device_ctx(null)
                    video_c!!.get_format(null)
                    video_c!!.thread_count(softwareVideoDecoderThreadCount(Runtime.getRuntime().availableProcessors()))
                    video_c!!.thread_type(AVCodecContext.FF_THREAD_FRAME or AVCodecContext.FF_THREAD_SLICE)
                    openResult = avcodec_open2(video_c, codec, vOptions)
                }
                if (openResult < 0) throw Exception("avcodec_open2() error $openResult: Could not open video codec.")
                FFmpegLogCallback.logRejectedOptions(vOptions, "avcodec_open2")
                av_dict_free(vOptions)

                if (video_c!!.time_base().num() > 1000 && video_c!!.time_base().den() == 1) video_c!!.time_base().den(1000)

                picture = av_frame_alloc()
                picture_rgb = av_frame_alloc()
                if (picture == null || picture_rgb == null) throw Exception("av_frame_alloc() error: Could not allocate picture frames.")
            }

            audio_st?.let { st ->
                var codec = if (audioCodecName != null) avcodec_find_decoder_by_name(audioCodecName) else null
                if (codec == null) codec = avcodec_find_decoder(audio_par!!.codec_id())
                if (codec ==
                    null
                ) {
                    throw Exception(
                        "avcodec_find_decoder() error: Unsupported audio format or codec not found: ${audio_par!!.codec_id()}.",
                    )
                }

                audio_c = avcodec_alloc_context3(codec)
                if (audio_c == null) throw Exception("avcodec_alloc_context3() error: Could not allocate audio decoding context.")

                if (avcodec_parameters_to_context(audio_c, st.codecpar()) < 0) {
                    releaseUnsafe()
                    throw Exception("avcodec_parameters_to_context() error: Could not copy the audio stream parameters.")
                }

                val aOptions = AVDictionary(null)
                for ((key, value) in audioOptions) av_dict_set(aOptions, key, value, 0)
                audio_c!!.thread_count(1)
                if (avcodec_open2(audio_c, codec, aOptions) < 0) throw Exception("avcodec_open2() error: Could not open audio codec.")
                FFmpegLogCallback.logRejectedOptions(aOptions, "avcodec_open2")
                av_dict_free(aOptions)

                samples_frame = av_frame_alloc()
                if (samples_frame == null) throw Exception("av_frame_alloc() error: Could not allocate audio frame.")

                samples_ptr = arrayOfNulls(1)
                samples_buf = arrayOfNulls(1)
            }
            started = true
        }
    }

    private fun initPictureRGB() {
        val width = if (super.imageWidth > 0) super.imageWidth else video_c?.takeUnless { it.isNull }?.width() ?: 0
        val height = if (super.imageHeight > 0) super.imageHeight else video_c?.takeUnless { it.isNull }?.height() ?: 0
        if (width <= 0 || height <= 0) return

        when (imageMode) {
            ImageMode.COLOR, ImageMode.GRAY -> {
                val fmt = pixelFormat
                if (!isValidSoftwareFormat(fmt)) return

                image_ptr?.let { av_free(it[0]) }
                image_buf = null
                image_ptr = null

                val align = 64
                var stride = width
                var i = 1
                while (i <= align) {
                    stride = (width + (i - 1)) and (i - 1).inv()
                    av_image_fill_linesizes(picture_rgb!!.linesize(), fmt, stride)
                    if ((picture_rgb!!.linesize(0) and (align - 1)) == 0) break
                    i *= 2
                }

                val size = av_image_get_buffer_size(fmt, stride, height, 1)
                if (size <= 0) return
                val ptr = BytePointer(av_malloc(size.toLong())).capacity(size.toLong())
                image_ptr = arrayOf(ptr)
                image_buf = arrayOf(ptr.asBuffer())

                av_image_fill_arrays(PointerPointer<Pointer>(picture_rgb), picture_rgb!!.linesize(), ptr, fmt, stride, height, 1)
                picture_rgb!!.format(fmt)
                picture_rgb!!.width(width)
                picture_rgb!!.height(height)
            }

            ImageMode.RAW -> {
                image_ptr = arrayOfNulls(1)
                image_buf = arrayOfNulls(1)
            }
        }
    }

    override fun stop() {
        release()
    }

    private fun getHwPixelFormat(hwDeviceType: Int): Int =
        when (hwDeviceType) {
            AV_HWDEVICE_TYPE_VIDEOTOOLBOX -> AV_PIX_FMT_VIDEOTOOLBOX
            AV_HWDEVICE_TYPE_D3D11VA -> AV_PIX_FMT_D3D11
            AV_HWDEVICE_TYPE_DXVA2 -> AV_PIX_FMT_DXVA2_VLD
            AV_HWDEVICE_TYPE_VAAPI -> AV_PIX_FMT_VAAPI
            AV_HWDEVICE_TYPE_CUDA -> AV_PIX_FMT_CUDA
            AV_HWDEVICE_TYPE_QSV -> AV_PIX_FMT_QSV
            AV_HWDEVICE_TYPE_VULKAN -> AV_PIX_FMT_VULKAN
            AV_HWDEVICE_TYPE_VDPAU -> AV_PIX_FMT_VDPAU
            AV_HWDEVICE_TYPE_MEDIACODEC -> AV_PIX_FMT_MEDIACODEC
            else -> AV_PIX_FMT_NONE
        }

    private fun isHardwareFormat(fmt: Int): Boolean {
        if (fmt < 0) return false
        if (fmt == AV_PIX_FMT_VIDEOTOOLBOX ||
            fmt == AV_PIX_FMT_D3D11 ||
            fmt == AV_PIX_FMT_DXVA2_VLD ||
            fmt == AV_PIX_FMT_VAAPI ||
            fmt == AV_PIX_FMT_CUDA ||
            fmt == AV_PIX_FMT_QSV ||
            fmt == AV_PIX_FMT_VULKAN ||
            fmt == AV_PIX_FMT_VDPAU ||
            fmt == AV_PIX_FMT_MEDIACODEC
        ) {
            return true
        }
        val desc = av_pix_fmt_desc_get(fmt)
        if (desc == null || desc.isNull) return false
        return (desc.flags().toInt() and 8) != 0
    }

    private fun isValidSoftwareFormat(fmt: Int): Boolean {
        if (fmt < 0 || fmt == AV_PIX_FMT_NONE) return false
        val desc = av_pix_fmt_desc_get(fmt)
        if (desc == null || desc.isNull) return false
        return (desc.flags().toInt() and 8) == 0
    }

    private fun softwarePicture(): AVFrame? {
        val pic = picture?.takeUnless { it.isNull } ?: return null
        val isHardwareFrame =
            (pic.hw_frames_ctx() != null && !pic.hw_frames_ctx().isNull) || isHardwareFormat(pic.format())
        if (!isHardwareFrame) return pic
        if (!hardwarePictureValid) {
            if (hardware_picture == null) hardware_picture = av_frame_alloc()
            val downloaded = hardware_picture?.takeUnless { it.isNull } ?: return null
            av_frame_unref(downloaded)
            if (av_hwframe_transfer_data(downloaded, pic, 0) < 0) return null
            av_frame_copy_props(downloaded, pic)
            hardwarePictureValid = true
        }
        return hardware_picture
    }

    fun processImage() {
        frame!!.imageWidth = if (super.imageWidth > 0) super.imageWidth else video_c?.takeUnless { it.isNull }?.width() ?: 0
        frame!!.imageHeight = if (super.imageHeight > 0) super.imageHeight else video_c?.takeUnless { it.isNull }?.height() ?: 0
        frame!!.imageDepth = Frame.DEPTH_UBYTE
        when (imageMode) {
            ImageMode.COLOR, ImageMode.GRAY -> {
                val sourceFrame = softwarePicture() ?: return

                val srcW = if (sourceFrame.width() > 0) sourceFrame.width() else video_c?.takeUnless { it.isNull }?.width() ?: 0
                val srcH = if (sourceFrame.height() > 0) sourceFrame.height() else video_c?.takeUnless { it.isNull }?.height() ?: 0
                val srcFmt = sourceFrame.format()
                val dstFmt = pixelFormat
                val dstW = frame!!.imageWidth
                val dstH = frame!!.imageHeight

                if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0 ||
                    !isValidSoftwareFormat(srcFmt) || !isValidSoftwareFormat(dstFmt)
                ) {
                    return
                }

                if (image_buf == null || dstW != picture_rgb!!.width() || dstH != picture_rgb!!.height()) initPictureRGB()
                av_frame_copy_props(picture_rgb, sourceFrame)
                img_convert_ctx =
                    sws_getCachedContext(
                        img_convert_ctx,
                        srcW,
                        srcH,
                        srcFmt,
                        dstW,
                        dstH,
                        dstFmt,
                        if (imageScalingFlags != 0) imageScalingFlags else SWS_FAST_BILINEAR,
                        null,
                        null,
                        null as DoublePointer?,
                    )
                if (img_convert_ctx == null) return
                sws_scale(
                    img_convert_ctx,
                    PointerPointer<Pointer>(sourceFrame),
                    sourceFrame.linesize(),
                    0,
                    srcH,
                    PointerPointer<Pointer>(picture_rgb),
                    picture_rgb!!.linesize(),
                )
                frame!!.imageStride = picture_rgb!!.linesize(0)
                frame!!.image = image_buf
                frame!!.opaque = picture_rgb
            }

            ImageMode.RAW -> {
                val pic = picture ?: return
                if (pic.isNull) return
                if (image_buf == null) initPictureRGB()
                frame!!.imageStride = pic.linesize(0)
                val ptr = pic.data(0)
                if (ptr != null && ptr != image_ptr!![0]) {
                    image_ptr!![0] = ptr.capacity((frame!!.imageHeight * frame!!.imageStride).toLong())
                    image_buf!![0] = ptr.asBuffer()
                }
                frame!!.image = image_buf
                frame!!.opaque = pic
            }
        }
        frame!!.image?.get(0)?.let { buf ->
            if (frame!!.imageStride > 0 && frame!!.imageHeight > 0) {
                buf.limit(frame!!.imageHeight * frame!!.imageStride)
                if (frame!!.imageWidth > 0) {
                    frame!!.imageChannels = frame!!.imageStride / frame!!.imageWidth
                }
            }
        }
    }

    /** Writes converted pixels directly into dstAddress using dstStride, without an intermediate buffer. */
    fun processImageToAddress(
        dstAddress: Long,
        dstStride: Int,
        destinationWidth: Int,
        destinationHeight: Int,
    ): Boolean {
        if (dstAddress == 0L || dstStride <= 0 || destinationWidth <= 0 || destinationHeight <= 0) return false
        val sourceFrame = softwarePicture() ?: return false

        val srcW = if (sourceFrame.width() > 0) sourceFrame.width() else video_c?.takeUnless { it.isNull }?.width() ?: 0
        val srcH = if (sourceFrame.height() > 0) sourceFrame.height() else video_c?.takeUnless { it.isNull }?.height() ?: 0
        val srcFmt = sourceFrame.format()
        val dstFmt = pixelFormat

        if (srcW <= 0 || srcH <= 0 || destinationWidth <= 0 || destinationHeight <= 0 ||
            !isValidSoftwareFormat(srcFmt) || !isValidSoftwareFormat(dstFmt)
        ) {
            return false
        }
        val minimumStride = av_image_get_linesize(dstFmt, destinationWidth, 0)
        if (minimumStride <= 0 || dstStride < minimumStride) return false

        img_convert_ctx =
            sws_getCachedContext(
                img_convert_ctx,
                srcW,
                srcH,
                srcFmt,
                destinationWidth,
                destinationHeight,
                dstFmt,
                if (imageScalingFlags != 0) imageScalingFlags else SWS_FAST_BILINEAR,
                null,
                null,
                null as DoublePointer?,
            ) ?: return false

        if (custom_target_picture == null) {
            custom_target_picture = av_frame_alloc()
        }
        val targetFrame = custom_target_picture ?: return false
        targetFrame.format(dstFmt)
        targetFrame.width(destinationWidth)
        targetFrame.height(destinationHeight)

        if (custom_target_ptr == null) {
            custom_target_ptr = CustomAddressPointer()
        }
        custom_target_ptr!!.setDirectAddress(dstAddress, dstStride.toLong() * destinationHeight)

        targetFrame.data(0, custom_target_ptr)
        targetFrame.linesize(0, dstStride)

        val scaledHeight =
            sws_scale(
                img_convert_ctx,
                sourceFrame.data(),
                sourceFrame.linesize(),
                0,
                srcH,
                targetFrame.data(),
                targetFrame.linesize(),
            )
        return scaledHeight > 0
    }

    data class PlanarFrameInfo(
        val width: Int,
        val height: Int,
        val pixelFormat: Int,
        val colorRange: Int,
        val colorSpace: Int,
    )

    private var planar_convert_ctx: SwsContext? = null
    private var planar_dst_ptr_y: CustomAddressPointer? = null
    private var planar_dst_ptr_u: CustomAddressPointer? = null
    private var planar_dst_ptr_v: CustomAddressPointer? = null
    private var planar_src_data: PointerPointer<BytePointer>? = null
    private var planar_src_linesize: IntPointer? = null
    private var planar_dst_data: PointerPointer<BytePointer>? = null
    private var planar_dst_linesize: IntPointer? = null

    fun processPlanarImageToAddresses(
        yAddress: Long,
        yStride: Int,
        uAddress: Long,
        uStride: Int,
        vAddress: Long,
        vStride: Int,
        destinationWidth: Int,
        destinationHeight: Int,
    ): PlanarFrameInfo? {
        if (yAddress == 0L || yStride <= 0) return null
        val sourceFrame = softwarePicture() ?: return null

        val srcW = if (sourceFrame.width() > 0) sourceFrame.width() else video_c?.takeUnless { it.isNull }?.width() ?: 0
        val srcH = if (sourceFrame.height() > 0) sourceFrame.height() else video_c?.takeUnless { it.isNull }?.height() ?: 0
        if (srcW <= 0 || srcH <= 0 || destinationWidth <= 0 || destinationHeight <= 0) return null
        val chromaWidth = (destinationWidth + 1) / 2
        if (yStride < destinationWidth || uStride < chromaWidth || vStride < chromaWidth || uAddress == 0L || vAddress == 0L) return null

        val srcFmt = sourceFrame.format()
        if (srcFmt != AV_PIX_FMT_NV12 && srcFmt != AV_PIX_FMT_NV21 && srcFmt != AV_PIX_FMT_YUV420P &&
            srcFmt != AV_PIX_FMT_YUVJ420P
        ) {
            return null
        }
        val srcLinesize0 = sourceFrame.linesize(0)
        val srcLinesize1 = sourceFrame.linesize(1)
        val srcLinesize2 = sourceFrame.linesize(2)
        val srcData0 = sourceFrame.data(0)
        val srcData1 = sourceFrame.data(1)
        val srcData2 = sourceFrame.data(2)

        if (srcData0 == null || srcData0.isNull) return null

        when {
            srcFmt == AV_PIX_FMT_NV12 || srcFmt == AV_PIX_FMT_NV21 ||
                !canDirectCopyPlanar(srcW, srcH, destinationWidth, destinationHeight) -> {
                if (srcData1 == null || srcData1.isNull) return null
                if ((srcFmt == AV_PIX_FMT_YUV420P || srcFmt == AV_PIX_FMT_YUVJ420P) && (srcData2 == null || srcData2.isNull)) return null
                val chromaH = (destinationHeight + 1) / 2
                planar_convert_ctx =
                    sws_getCachedContext(
                        planar_convert_ctx,
                        srcW,
                        srcH,
                        srcFmt,
                        destinationWidth,
                        destinationHeight,
                        if (srcFmt == AV_PIX_FMT_YUVJ420P) AV_PIX_FMT_YUVJ420P else AV_PIX_FMT_YUV420P,
                        if (canDirectCopyPlanar(srcW, srcH, destinationWidth, destinationHeight)) SWS_POINT else SWS_BILINEAR,
                        null,
                        null,
                        null as DoublePointer?,
                    )
                val sws = planar_convert_ctx ?: return null

                if (planar_dst_ptr_y == null) planar_dst_ptr_y = CustomAddressPointer()
                if (planar_dst_ptr_u == null) planar_dst_ptr_u = CustomAddressPointer()
                if (planar_dst_ptr_v == null) planar_dst_ptr_v = CustomAddressPointer()
                planar_dst_ptr_y!!.setDirectAddress(yAddress, yStride.toLong() * destinationHeight)
                planar_dst_ptr_u!!.setDirectAddress(uAddress, (uStride * chromaH).toLong())
                planar_dst_ptr_v!!.setDirectAddress(vAddress, (vStride * chromaH).toLong())

                if (planar_src_data == null) planar_src_data = PointerPointer<BytePointer>(4).retainReference()
                if (planar_src_linesize == null) planar_src_linesize = IntPointer(4).retainReference()
                if (planar_dst_data == null) planar_dst_data = PointerPointer<BytePointer>(4).retainReference()
                if (planar_dst_linesize == null) planar_dst_linesize = IntPointer(4).retainReference()

                planar_src_data!!.put(0L, srcData0)
                planar_src_data!!.put(1L, srcData1)
                planar_src_data!!.put(2L, srcData2)
                planar_src_data!!.put(3L, null as BytePointer?)

                planar_src_linesize!!.put(0L, srcLinesize0)
                planar_src_linesize!!.put(1L, srcLinesize1)
                planar_src_linesize!!.put(2L, srcLinesize2)
                planar_src_linesize!!.put(3L, 0)

                planar_dst_data!!.put(0L, planar_dst_ptr_y)
                planar_dst_data!!.put(1L, planar_dst_ptr_u)
                planar_dst_data!!.put(2L, planar_dst_ptr_v)
                planar_dst_data!!.put(3L, null as BytePointer?)

                planar_dst_linesize!!.put(0L, yStride)
                planar_dst_linesize!!.put(1L, uStride)
                planar_dst_linesize!!.put(2L, vStride)
                planar_dst_linesize!!.put(3L, 0)

                val ret =
                    sws_scale(
                        sws,
                        planar_src_data,
                        planar_src_linesize,
                        0,
                        srcH,
                        planar_dst_data,
                        planar_dst_linesize,
                    )
                if (ret <= 0) return null
            }

            else -> {
                if (srcData1 == null || srcData1.isNull || uAddress == 0L || uStride <= 0) return null
                val copyW = minOf(srcW, yStride, srcLinesize0)
                copyPlane(srcData0.address(), srcLinesize0, yAddress, yStride, copyW, srcH)
                val chromaW = (srcW + 1) / 2
                val chromaH = (srcH + 1) / 2
                val chromaCopyW = minOf(chromaW, uStride, srcLinesize1)
                copyPlane(srcData1.address(), srcLinesize1, uAddress, uStride, chromaCopyW, chromaH)
                if (srcData2 != null && !srcData2.isNull && vAddress != 0L && vStride > 0) {
                    val chromaCopyW2 = minOf(chromaW, vStride, srcLinesize2)
                    copyPlane(srcData2.address(), srcLinesize2, vAddress, vStride, chromaCopyW2, chromaH)
                }
            }
        }

        return PlanarFrameInfo(
            width = destinationWidth,
            height = destinationHeight,
            pixelFormat = AV_PIX_FMT_YUV420P,
            colorRange = sourceFrame.color_range(),
            colorSpace = sourceFrame.colorspace(),
        )
    }

    private val copySrcPtr by lazy { CustomAddressPointer() }
    private val copyDstPtr by lazy { CustomAddressPointer() }

    private fun copyPlane(
        srcAddr: Long,
        srcStride: Int,
        dstAddr: Long,
        dstStride: Int,
        widthBytes: Int,
        heightLines: Int,
    ) {
        if (srcAddr == 0L || dstAddr == 0L || widthBytes <= 0 || heightLines <= 0) return
        val copyW = minOf(widthBytes, dstStride, srcStride)
        val totalSrc = srcStride.toLong() * heightLines
        val totalDst = dstStride.toLong() * heightLines
        copySrcPtr.setDirectAddress(srcAddr, totalSrc)
        copyDstPtr.setDirectAddress(dstAddr, totalDst)
        av_image_copy_plane(copyDstPtr, dstStride, copySrcPtr, srcStride, copyW, heightLines)
    }

    private fun processSamples() {
        val sFrame = samples_frame ?: return
        if (sFrame.isNull) return
        val audioCtx = audio_c ?: return
        if (audioCtx.isNull) return

        val sample_format = sFrame.format()
        if (sample_format == AV_SAMPLE_FMT_NONE || sFrame.nb_samples() <= 0) return
        val nbChannels = audioCtx.ch_layout().nb_channels()
        if (nbChannels !in 1..AVFrame.AV_NUM_DATA_POINTERS) return

        val planes = if (av_sample_fmt_is_planar(sample_format) != 0) sFrame.ch_layout().nb_channels() else 1
        if (planes !in 1..AVFrame.AV_NUM_DATA_POINTERS) return
        val data_size =
            av_samples_get_buffer_size(
                null as IntPointer?,
                nbChannels,
                sFrame.nb_samples(),
                audioCtx.sample_fmt(),
                1,
            ) / planes
        if (data_size <= 0) return

        if (samples_buf == null || samples_buf!!.size != planes) {
            samples_ptr = arrayOfNulls(planes)
            samples_buf = arrayOfNulls(planes)
        }
        frame!!.sampleRate = sampleRate
        frame!!.audioChannels = audioChannels
        frame!!.samples = samples_buf
        frame!!.opaque = sFrame
        val bytesPerSample = av_get_bytes_per_sample(sample_format)
        if (bytesPerSample <= 0) return
        val sample_size = data_size / bytesPerSample
        for (i in 0 until planes) {
            val p = sFrame.data(i)
            if (p != null && !p.isNull) {
                if (p != samples_ptr!![i] || samples_ptr!![i]!!.capacity() < data_size.toLong()) {
                    samples_ptr!![i] = p.capacity(data_size.toLong())
                    val b = p.asBuffer()
                    when (sample_format) {
                        AV_SAMPLE_FMT_U8, AV_SAMPLE_FMT_U8P -> samples_buf!![i] = b
                        AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P -> samples_buf!![i] = b.asShortBuffer()
                        AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S32P -> samples_buf!![i] = b.asIntBuffer()
                        AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_FLTP -> samples_buf!![i] = b.asFloatBuffer()
                        AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_DBLP -> samples_buf!![i] = b.asDoubleBuffer()
                    }
                }
                samples_buf!![i]?.position(0)?.limit(sample_size)
            }
        }

        if (audioCtx.ch_layout().nb_channels() != audioChannels || audioCtx.sample_fmt() != sampleFormat ||
            audioCtx.sample_rate() != sampleRate
        ) {
            if (samples_convert_ctx == null || samples_channels != audioChannels || samples_format != sampleFormat ||
                samples_rate != sampleRate
            ) {
                if (samples_convert_ctx == null) samples_convert_ctx = SwrContext().retainReference()
                av_channel_layout_default(default_layout, audioChannels)
                if (swr_alloc_set_opts2(
                        samples_convert_ctx,
                        default_layout,
                        sampleFormat,
                        sampleRate,
                        audioCtx.ch_layout(),
                        audioCtx.sample_fmt(),
                        audioCtx.sample_rate(),
                        0,
                        null,
                    ) < 0
                ) {
                    return
                } else if (swr_init(samples_convert_ctx) < 0) {
                    return
                }
                samples_channels = audioChannels
                samples_format = sampleFormat
                samples_rate = sampleRate
            }

            val sample_size_in = sFrame.nb_samples()
            val planes_out = if (av_sample_fmt_is_planar(samples_format) != 0) audioChannels else 1
            if (planes_out !in 1..AVFrame.AV_NUM_DATA_POINTERS) return
            val sample_size_out = swr_get_out_samples(samples_convert_ctx, sample_size_in)
            val sample_bytes_out = av_get_bytes_per_sample(samples_format)
            if (sample_bytes_out <= 0 || sample_size_out <= 0) return
            val bufferSizeOut =
                sample_size_out.toLong() * sample_bytes_out * (if (planes_out > 1) 1 else samples_channels)
            if (bufferSizeOut !in 1..Int.MAX_VALUE.toLong()) return
            val buffer_size_out = bufferSizeOut.toInt()
            if (samples_buf_out == null || samples_buf_out!!.size != planes_out ||
                samples_ptr_out!![0]!!.capacity() < buffer_size_out.toLong()
            ) {
                samples_ptr_out?.let { for (ptr in it) av_free(ptr?.position(0L)) }
                samples_ptr_out = arrayOfNulls(planes_out)
                samples_buf_out = arrayOfNulls(planes_out)

                for (i in 0 until planes_out) {
                    val ptr = BytePointer(av_malloc(buffer_size_out.toLong())).capacity(buffer_size_out.toLong())
                    samples_ptr_out!![i] = ptr
                    val b = ptr.asBuffer()
                    when (samples_format) {
                        AV_SAMPLE_FMT_U8, AV_SAMPLE_FMT_U8P -> samples_buf_out!![i] = b
                        AV_SAMPLE_FMT_S16, AV_SAMPLE_FMT_S16P -> samples_buf_out!![i] = b.asShortBuffer()
                        AV_SAMPLE_FMT_S32, AV_SAMPLE_FMT_S32P -> samples_buf_out!![i] = b.asIntBuffer()
                        AV_SAMPLE_FMT_FLT, AV_SAMPLE_FMT_FLTP -> samples_buf_out!![i] = b.asFloatBuffer()
                        AV_SAMPLE_FMT_DBL, AV_SAMPLE_FMT_DBLP -> samples_buf_out!![i] = b.asDoubleBuffer()
                    }
                }
            }
            frame!!.sampleRate = sampleRate
            frame!!.audioChannels = audioChannels
            frame!!.samples = samples_buf_out

            val ptrOut = plane_ptr ?: return
            val ptrIn = plane_ptr2 ?: return
            val inputPlanes = samples_ptr?.size ?: return
            if (planes_out > ptrOut.capacity() || inputPlanes > ptrIn.capacity()) return
            for (i in 0 until planes_out) ptrOut.put(i.toLong(), samples_ptr_out!![i])
            for (i in 0 until inputPlanes) ptrIn.put(i.toLong(), samples_ptr!![i])

            val ret = swr_convert(samples_convert_ctx, ptrOut, sample_size_out, ptrIn, sample_size_in)
            if (ret < 0) {
                return
            }
            val outLimit = ret * (if (planes_out > 1) 1 else audioChannels)
            for (i in 0 until planes_out) {
                samples_ptr_out!![i]?.position(0L)?.limit(outLimit.toLong())
                samples_buf_out!![i]?.position(0)?.limit(outLimit)
            }
        }
    }

    override fun grab(): Frame? = grabFrame(true, true, true, false, true)

    fun grabSamples(): Frame? = grabFrame(true, false, true, false, false)

    fun grabFrame(
        doAudio: Boolean,
        doVideo: Boolean,
        doProcessing: Boolean,
        keyFrames: Boolean,
    ): Frame? = grabFrame(doAudio, doVideo, doProcessing, keyFrames, true)

    @Synchronized
    fun grabFrame(
        doAudio: Boolean,
        doVideo: Boolean,
        doProcessing: Boolean,
        keyFrames: Boolean,
        doData: Boolean,
    ): Frame? {
        PointerScope().use { _ ->
            if (oc == null || oc!!.isNull) throw Exception("Could not grab: No AVFormatContext. (Has start() been called?)")
            if ((!doVideo || video_st == null) && (!doAudio || audio_st == null) && !doData) return null
            if (!started) throw Exception("start() was not called successfully!")

            val videoFrameGrabbed = frameGrabbed && frame!!.type == Frame.Type.VIDEO
            val audioFrameGrabbed = frameGrabbed && frame!!.samples != null
            val dataFrameGrabbed = frameGrabbed && frame!!.data != null
            frameGrabbed = false

            if (doVideo && videoFrameGrabbed) {
                if (doProcessing) processImage()
                frame!!.keyFrame = (picture!!.flags() and AVFrame.AV_FRAME_FLAG_KEY) != 0
                return frame
            } else if (doAudio && audioFrameGrabbed) {
                if (doProcessing) processSamples()
                frame!!.keyFrame = (samples_frame!!.flags() and AVFrame.AV_FRAME_FLAG_KEY) != 0
                return frame
            } else if (doData && dataFrameGrabbed) {
                return frame
            }

            frame!!.keyFrame = false
            frame!!.imageWidth = 0
            frame!!.imageHeight = 0
            frame!!.imageDepth = 0
            frame!!.imageChannels = 0
            frame!!.imageStride = 0
            frame!!.image = null
            frame!!.sampleRate = 0
            frame!!.audioChannels = 0
            frame!!.samples = null
            frame!!.data = null
            frame!!.opaque = null
            frame!!.type = null

            var done = false
            var readPacket = pkt!!.stream_index() == -1
            while (!done) {
                if (readPacket) {
                    if (pkt!!.stream_index() != -1) {
                        av_packet_unref(pkt)
                        pkt!!.stream_index(-1)
                    }
                    var ret = av_read_frame(oc, pkt)
                    if (ret < 0) {
                        if (ret == AVERROR_EAGAIN()) {
                            try {
                                Thread.sleep(10)
                            } catch (ex: InterruptedException) {
                                Thread.currentThread().interrupt()
                                return null
                            }
                            continue
                        }
                        if (Thread.currentThread().isInterrupted) return null
                        if (ret != AVERROR_EOF()) {
                            val message = "av_read_frame() error $ret: ${ffmpegErrorDescription(ret)}"
                            logNativeMessage(AV_LOG_ERROR, message)
                        }
                        if ((doVideo && video_st != null) || (doAudio && audio_st != null)) {
                            pkt!!.stream_index(if (doVideo && video_st != null) video_st!!.index() else audio_st!!.index())
                            pkt!!.flags(AV_PKT_FLAG_KEY)
                            pkt!!.data(null as BytePointer?)
                            pkt!!.size(0)
                        } else {
                            pkt!!.stream_index(-1)
                            return null
                        }
                    }
                }

                frame!!.streamIndex = pkt!!.stream_index()

                if (doVideo && video_st != null && frame!!.streamIndex == video_st!!.index() &&
                    (!keyFrames || pkt!!.flags() == AV_PKT_FLAG_KEY)
                ) {
                    if (readPacket) {
                        val result = avcodec_send_packet(video_c, pkt)
                        if (result < 0 && result != AVERROR_EAGAIN() && result != AVERROR_EOF()) {
                            logNativeMessage(AV_LOG_ERROR, "avcodec_send_packet(video) error $result: ${ffmpegErrorDescription(result)}")
                        }
                        if (pkt!!.data() == null && pkt!!.size() == 0) pkt!!.stream_index(-1)
                    }

                    while (!done) {
                        hardwarePictureValid = false
                        var ret = avcodec_receive_frame(video_c, picture)
                        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) {
                            if (pkt!!.data() == null && pkt!!.size() == 0) {
                                pkt!!.stream_index(-1)
                                return null
                            } else {
                                readPacket = true
                                break
                            }
                        } else if (ret < 0) {
                            logNativeMessage(AV_LOG_ERROR, "avcodec_receive_frame(video) error $ret: ${ffmpegErrorDescription(ret)}")
                            readPacket = true
                            break
                        }

                        if (!keyFrames || picture!!.pict_type() == AV_PICTURE_TYPE_I) {
                            val pts = picture!!.best_effort_timestamp()
                            val tb = video_st!!.time_base()
                            _timestamp = 1000000L * pts * tb.num() / tb.den()
                            val ts0 = if (oc!!.start_time() != AV_NOPTS_VALUE) oc!!.start_time() else 0L
                            _frameNumber =
                                ((_timestamp - ts0) * frameRate / 1000000L)
                                    .roundToLong()
                                    .toInt()
                            frame!!.image = image_buf
                            if (doProcessing) processImage()
                            done = true
                            frame!!.timestamp = _timestamp
                            frame!!.keyFrame = (picture!!.flags() and AVFrame.AV_FRAME_FLAG_KEY) != 0
                            frame!!.pictType = av_get_picture_type_char(picture!!.pict_type()).toInt().toChar()
                            frame!!.type = Frame.Type.VIDEO
                        }
                    }
                } else if (doAudio && audio_st != null && frame!!.streamIndex == audio_st!!.index()) {
                    if (readPacket) avcodec_send_packet(audio_c, pkt)

                    while (!done) {
                        var ret = avcodec_receive_frame(audio_c, samples_frame)
                        if (ret == AVERROR_EAGAIN() || ret == AVERROR_EOF()) {
                            if (pkt!!.data() == null && pkt!!.size() == 0) {
                                pkt!!.stream_index(-1)
                                return null
                            } else {
                                readPacket = true
                                break
                            }
                        } else if (ret < 0) {
                            readPacket = true
                            break
                        }

                        val pts = samples_frame!!.best_effort_timestamp()
                        val tb = audio_st!!.time_base()
                        _timestamp = 1000000L * pts * tb.num() / tb.den()
                        frame!!.samples = samples_buf
                        if (doProcessing) processSamples()
                        done = true
                        frame!!.timestamp = _timestamp
                        frame!!.keyFrame = (samples_frame!!.flags() and AVFrame.AV_FRAME_FLAG_KEY) != 0
                        frame!!.type = Frame.Type.AUDIO
                    }
                } else if (readPacket && doData && frame!!.streamIndex > -1 && frame!!.streamIndex < streams!!.size &&
                    streams!![frame!!.streamIndex] != AVMEDIA_TYPE_VIDEO &&
                    streams!![frame!!.streamIndex] != AVMEDIA_TYPE_AUDIO
                ) {
                    frame!!.data =
                        pkt!!
                            .data()
                            .position(0L)
                            .capacity(pkt!!.size().toLong())
                            .asByteBuffer()
                    frame!!.opaque = pkt
                    done = true
                    when (streams!![frame!!.streamIndex]) {
                        AVMEDIA_TYPE_DATA -> frame!!.type = Frame.Type.DATA
                        AVMEDIA_TYPE_SUBTITLE -> frame!!.type = Frame.Type.SUBTITLE
                        AVMEDIA_TYPE_ATTACHMENT -> frame!!.type = Frame.Type.ATTACHMENT
                        else -> frame!!.type = null
                    }
                } else {
                    readPacket = true
                }
            }
            return frame
        }
    }

    override var frameNumber: Int
        get() = _frameNumber
        set(value) {
            if (hasVideo()) {
                setTimestamp(((1000000L * value + 500000L) / frameRate).roundToLong(), true)
            } else {
                _frameNumber = value
            }
        }

    override var timestamp: Long
        get() = _timestamp
        set(value) {
            setTimestamp(value, false)
        }

    @Synchronized
    fun setTimestamp(
        timestamp: Long,
        checkFrame: Boolean,
    ) {
        hardwarePictureValid = false
        frameGrabbed = false
        var ts = timestamp
        if (oc == null || oc!!.isNull) {
            _timestamp = ts
        } else {
            ts = ts * AV_TIME_BASE / 1000000L
            var ts0 = if (oc!!.start_time() != AV_NOPTS_VALUE) oc!!.start_time() else 0L

            if (checkFrame && (hasVideo() || hasAudio())) {
                var early_ts = ts + ts0
                ts += ts0

                var initialSeekPosition: Long
                var maxSeekSteps = 0L
                var count: Long
                var seekFrame: Frame?
                do {
                    var ret = avformat_seek_file(oc, -1, Long.MIN_VALUE, early_ts, early_ts, AVSEEK_FLAG_BACKWARD)
                    if (ret < 0) {
                        ret = avformat_seek_file(oc, -1, Long.MIN_VALUE, early_ts, Long.MAX_VALUE, AVSEEK_FLAG_BACKWARD)
                    }
                    if (ret < 0 && (timestamp == 0L || early_ts == 0L)) {
                        ret = av_seek_frame(oc, -1, 0L, AVSEEK_FLAG_BACKWARD)
                    }
                    if (ret < 0) {
                        throw Exception("avformat_seek_file() error $ret: Could not seek file to timestamp $timestamp.")
                    }
                    video_c?.let { avcodec_flush_buffers(it) }
                    audio_c?.let { avcodec_flush_buffers(it) }
                    pkt?.let {
                        if (it.stream_index() != -1) {
                            av_packet_unref(it)
                            it.stream_index(-1)
                        }
                    }

                    seekFrame = grabFrame(true, true, false, false, false)
                    if (seekFrame == null) return
                    initialSeekPosition = seekFrame.timestamp
                    if (early_ts == 0L) break
                    early_ts -= 500000L
                    if (early_ts < 0) early_ts = 0L
                } while (initialSeekPosition > ts)

                var frameDuration = 0.0
                if (seekFrame.type == Frame.Type.VIDEO && frameRate > 0) {
                    frameDuration = AV_TIME_BASE / frameRate
                } else if (seekFrame.samples != null && samples_frame != null && sampleRate > 0) {
                    frameDuration = AV_TIME_BASE * samples_frame!!.nb_samples() / sampleRate.toDouble()
                }

                if (frameDuration > 0.0) {
                    if (ts - initialSeekPosition + 1 > frameDuration) {
                        maxSeekSteps = (10 * (ts - initialSeekPosition) / frameDuration).toLong()
                    }
                } else if (initialSeekPosition < ts) {
                    maxSeekSteps = 1000L
                }

                count = 0
                while (count < maxSeekSteps) {
                    seekFrame = grabFrame(true, true, false, false, false)
                    if (seekFrame == null) return
                    count++
                    var currentTs = seekFrame.timestamp.toDouble()
                    frameDuration = 0.0
                    if (seekFrame.type == Frame.Type.VIDEO && frameRate > 0) {
                        frameDuration = AV_TIME_BASE / frameRate
                    } else if (seekFrame.samples != null && samples_frame != null && sampleRate > 0) {
                        frameDuration = AV_TIME_BASE * samples_frame!!.nb_samples() / sampleRate.toDouble()
                    }

                    var delta = 0.0
                    if (frameDuration > 0.0) {
                        delta = (currentTs - ts0) / frameDuration - ((currentTs - ts0) / frameDuration).roundToLong()
                        if (abs(delta) > 0.2) delta = 0.0
                    }
                    currentTs -= delta * frameDuration
                    if (currentTs + frameDuration > ts) break
                }
            } else {
                ts += ts0
                var ret = avformat_seek_file(oc, -1, Long.MIN_VALUE, ts, Long.MAX_VALUE, AVSEEK_FLAG_BACKWARD)
                if (ret < 0 && timestamp == 0L) {
                    ret = av_seek_frame(oc, -1, 0L, AVSEEK_FLAG_BACKWARD)
                }
                if (ret < 0) {
                    throw Exception("avformat_seek_file() error $ret: Could not seek file to timestamp $timestamp.")
                }
                video_c?.let { avcodec_flush_buffers(it) }
                audio_c?.let { avcodec_flush_buffers(it) }
                pkt?.let {
                    if (it.stream_index() != -1) {
                        av_packet_unref(it)
                        it.stream_index(-1)
                    }
                }

                var count = 0
                while (this._timestamp > timestamp + 1 && grabFrame(true, true, false, false) != null && count++ < 1000) {}
                count = 0
                while (this._timestamp < timestamp - 1 && grabFrame(true, true, false, false) != null && count++ < 1000) {}
            }
            frameGrabbed = true
        }
    }
}

internal class CustomAddressPointer : BytePointer() {
    fun setDirectAddress(
        addr: Long,
        size: Long,
    ) {
        this.address = addr
        this.capacity = size
        this.limit = size
        this.position = 0
    }
}
