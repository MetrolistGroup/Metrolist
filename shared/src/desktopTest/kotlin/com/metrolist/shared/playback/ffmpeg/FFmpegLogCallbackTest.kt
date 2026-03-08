package com.metrolist.shared.playback.ffmpeg

import com.metrolist.shared.utils.LogBridge
import com.metrolist.shared.youtube.playback.PlaybackEventLog
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_ERROR
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_WARNING
import org.bytedeco.ffmpeg.global.avutil.av_log
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FFmpegLogCallbackTest {
    @Test
    fun nativeWarningsAndErrorsReachBothLogsWithoutSignedUrls() {
        val previous = LogBridge.sink
        val messages = mutableListOf<String>()
        LogBridge.sink = { level, _, message, _ -> messages += "$level $message" }
        PlaybackEventLog.clear()
        try {
            FFmpegFrameGrabber.tryLoad()
            av_log(null, AV_LOG_ERROR, "HTTP 403 https://cdn.example/video?sig=secret\n")
            av_log(null, AV_LOG_WARNING, "decoder warning\n")
            assertEquals(2, messages.size)
            assertTrue(messages.first().startsWith("ERROR HTTP 403"))
            assertFalse(messages.first().contains("secret"))
            val exported = PlaybackEventLog.exportText()
            assertTrue(exported.contains("HTTP 403"))
            assertTrue(exported.contains("decoder warning"))
            assertFalse(exported.contains("secret"))
        } finally {
            LogBridge.sink = previous
            PlaybackEventLog.clear()
        }
    }
}
